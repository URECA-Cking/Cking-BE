package kr.co.cking.common.storage;

import java.net.URI;
import java.util.Map;

/** 브라우저가 그대로 보내야 하는 주소와 헤더. 헤더는 서명에 들어가 바꾸면 S3가 거절한다. */
public record PresignedUpload(URI url, Map<String, String> headers) {
}
