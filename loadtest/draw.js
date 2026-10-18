// 추첨: 마감(CLOSED)된 이벤트로 INITIAL 추첨을 실행하고 재현 검증을 부른다.
// 마감과 Snapshot 생성은 앱 스케줄러가 자동으로 한다. 이벤트가 CLOSED가 된 뒤 실행한다.
import http from 'k6/http';
import { check, fail } from 'k6';
import { BASE_URL, positiveInt } from './lib/api.js';
import { tokenFactory } from './lib/token.js';

const EVENT_ID = positiveInt('CLOSING_EVENT_ID');
const adminToken = tokenFactory(__ENV.JWT_SECRET || '', 600)(positiveInt('ADMIN_MEMBER_ID'), 'ADMIN');
const headers = { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' };

export const options = { vus: 1, iterations: 1 };

export default function () {
  const drawing = http.post(`${BASE_URL}/api/admin/events/${EVENT_ID}/drawings`, null,
    { headers, tags: { name: 'draw' } });
  if (!check(drawing, { '추첨 실행 200': (r) => r.status === 200 })) {
    fail(`추첨 실행 실패: HTTP ${drawing.status} ${safeCode(drawing)}`);
  }
  const drawingId = drawing.json('data.drawingId');

  const verify = http.post(`${BASE_URL}/api/admin/drawings/${drawingId}/verify`, null,
    { headers, tags: { name: 'draw_verify' } });
  check(verify, {
    '재현 검증 200': (r) => r.status === 200,
    '재현 검증 VERIFIED': (r) => r.status === 200 && r.json('data.status') === 'VERIFIED',
  });

  console.log(JSON.stringify({ kind: 'draw', eventId: EVENT_ID, drawingId,
    drawMs: drawing.timings.duration, verifyMs: verify.timings.duration,
    drawing: drawing.json('data'), verification: verify.json('data') }));
}

function safeCode(response) {
  try { return response.json('code'); } catch (_) { return ''; }
}
