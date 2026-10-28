// 이미지 업로드 부하: 고정 도착률로 회원 프로필 이미지를 올리면서 업로드와 무관한 조회를 함께 보낸다.
// MODE=legacy는 앱 안에서 정규화하는 기존 업로드, MODE=pipeline은 예약 → S3 PUT → 상태 확인(#780).
// 같은 도착률로 두 방식을 재서 파이프라인을 켜기 전·후를 비교한다.
import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { open, SeekMode } from 'k6/experimental/fs';
import { Counter, Trend } from 'k6/metrics';
import { BASE_URL, memberOf, positiveInt, tokenOf } from './lib/api.js';

const MODE = __ENV.MODE;
if (MODE !== 'legacy' && MODE !== 'pipeline') {
  throw new Error('MODE는 legacy 또는 pipeline이어야 합니다.');
}
const RATE = positiveInt('RATE');
const LARGE_RATIO = Number(__ENV.LARGE_RATIO || '0.2');
const POLL_TIMEOUT_MS = 30000;

// 원본은 k6 서버 파일에서 읽는다. fs 모듈은 파일을 한 번만 메모리에 올려 VU끼리 나눠 쓴다.
// open()으로 VU마다 사본을 두면 원본 약 9MB × VU 수라 c7g.large(4GB)도 16건/초에서 메모리가 찬다.
const small = await open(__ENV.IMAGE_SMALL || '/opt/loadtest/images/img-12mp.jpg');
const large = await open(__ENV.IMAGE_LARGE || '/opt/loadtest/images/img-20mp.jpg');

const uploadResults = new Counter('upload_results');
const endToEnd = new Trend('upload_end_to_end', true);
const RESULTS = ['ready', 'failed', 'timeout', 'reserve_fail', 'put_fail', 'created', 'rejected'];

export const options = {
  scenarios: {
    upload: {
      executor: 'constant-arrival-rate',
      exec: 'upload',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: positiveInt('PRE_VUS', RATE * 6),
      // 파이프라인은 처리가 밀리면 업로드 하나가 최대 30초 VU를 잡는다
      maxVUs: positiveInt('MAX_VUS', RATE * 40),
    },
    probe: {
      executor: 'constant-arrival-rate',
      exec: 'probe',
      rate: positiveInt('PROBE_RATE', 10),
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 20,
      maxVUs: 50,
    },
  },
  // 결과별 건수가 요약(--summary-export)에 나오도록 부분 지표마다 항상 통과하는 기준을 둔다
  thresholds: Object.fromEntries([
    ...RESULTS.map((result) => [`upload_results{result:${result}}`, ['count>=0']]),
    ['upload_end_to_end', ['max>=0']],
    ['http_req_duration{name:probe}', ['max>=0']],
    ['http_req_failed{name:probe}', ['rate>=0']],
  ]),
};

async function readImage() {
  const file = Math.random() < LARGE_RATIO ? large : small;
  const { size } = await file.stat();
  const buffer = new Uint8Array(size);
  await file.seek(0, SeekMode.Start);
  await file.read(buffer);
  return buffer.buffer;
}

function authHeaders(memberId) {
  return { Authorization: `Bearer ${tokenOf(memberId)}` };
}

export async function upload() {
  const memberId = memberOf(exec.scenario.iterationInTest);
  const image = await readImage();
  if (MODE === 'legacy') {
    legacyUpload(memberId, image);
  } else {
    pipelineUpload(memberId, image);
  }
}

function legacyUpload(memberId, image) {
  const response = http.post(`${BASE_URL}/api/me/profile-images`,
    { image: http.file(image, 'load.jpg', 'image/jpeg') },
    { headers: authHeaders(memberId), timeout: '120s', responseType: 'none', tags: { name: 'upload_legacy' } });
  uploadResults.add(1, { result: response.status === 201 ? 'created' : 'rejected', status: String(response.status) });
}

function pipelineUpload(memberId, image) {
  const start = Date.now();
  const headers = { ...authHeaders(memberId), 'Content-Type': 'application/json' };
  const reserved = http.post(`${BASE_URL}/api/uploads`,
    JSON.stringify({ purpose: 'MEMBER_PROFILE', contentType: 'image/jpeg', size: image.byteLength }),
    { headers, tags: { name: 'upload_reserve' } });
  if (reserved.status !== 201) {
    uploadResults.add(1, { result: 'reserve_fail', status: String(reserved.status) });
    return;
  }
  const { uploadId, uploadUrl, headers: signedHeaders } = reserved.json('data');
  const put = http.put(uploadUrl, image,
    { headers: signedHeaders, timeout: '60s', responseType: 'none', tags: { name: 'upload_put' } });
  if (put.status !== 200) {
    uploadResults.add(1, { result: 'put_fail', status: String(put.status) });
    return;
  }
  // FE(imageUploadFlow.js)와 같은 간격으로 확인한다
  const pollStart = Date.now();
  for (;;) {
    const status = http.get(`${BASE_URL}/api/uploads/${uploadId}`, { headers, tags: { name: 'upload_status' } });
    const state = status.status === 200 ? status.json('data.status') : null;
    if (state === 'READY') {
      uploadResults.add(1, { result: 'ready' });
      endToEnd.add(Date.now() - start);
      return;
    }
    if (state === 'FAILED') {
      uploadResults.add(1, { result: 'failed' });
      return;
    }
    const elapsed = Date.now() - pollStart;
    if (elapsed >= POLL_TIMEOUT_MS) {
      uploadResults.add(1, { result: 'timeout' });
      return;
    }
    sleep(elapsed < 3000 ? 0.5 : elapsed < 6000 ? 1 : 2);
  }
}

export function probe() {
  http.get(`${BASE_URL}/api/events?page=0&size=10`, { timeout: '30s', responseType: 'none', tags: { name: 'probe' } });
}
