package kr.co.cking.quiz.application;

import kr.co.cking.common.exception.BusinessException;
import kr.co.cking.common.exception.CommonErrorCode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creator가 입력한 YouTube URL에서 videoId만 꺼낸다(이슈 #525). URL을 요청하지 않고 문자열만 검사한다.
 *
 * <ul>
 *   <li>https만, 호스트는 {@code youtube.com}·{@code www.youtube.com}·{@code m.youtube.com}·{@code youtu.be}만</li>
 *   <li>userinfo와 443이 아닌 port는 거부</li>
 *   <li>경로: {@code /watch?v=<id>}, {@code /shorts/<id>}, {@code youtu.be/<id>}. videoId는 정확히 11자
 *       {@code [A-Za-z0-9_-]}이며 퍼센트 인코딩된 값은 거부</li>
 *   <li>{@code v} 파라미터가 둘 이상이면 거부. 그 밖의 query와 fragment는 허용하지만 쓰지 않는다</li>
 * </ul>
 * 형식이 맞지 않으면 {@code VALIDATION_FAILED}다.
 */
public final class YoutubeVideoUrl {

    private static final String VIDEO_ID = "[A-Za-z0-9_-]{11}";
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(VIDEO_ID);
    private static final Pattern SHORTS_PATH = Pattern.compile("/shorts/(" + VIDEO_ID + ")/?");
    private static final Pattern SHORT_LINK_PATH = Pattern.compile("/(" + VIDEO_ID + ")");
    private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com");
    private static final String SHORT_LINK_HOST = "youtu.be";

    private YoutubeVideoUrl() {
    }

    public static String videoIdOf(String url) {
        URI uri;
        try {
            uri = new URI(url == null ? "" : url);
        } catch (URISyntaxException exception) {
            throw invalid();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getHost() == null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawPath() == null) {
            throw invalid();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath();
        if (SHORT_LINK_HOST.equals(host)) {
            return group(SHORT_LINK_PATH.matcher(path));
        }
        if (!YOUTUBE_HOSTS.contains(host)) {
            throw invalid();
        }
        if ("/watch".equals(path)) {
            return watchVideoId(uri.getRawQuery());
        }
        return group(SHORTS_PATH.matcher(path));
    }

    /** 원문 그대로의 query에서 {@code v}를 정확히 하나 찾는다. 디코딩하지 않으므로 인코딩된 값은 형식에서 걸린다. */
    private static String watchVideoId(String rawQuery) {
        if (rawQuery == null) {
            throw invalid();
        }
        String videoId = null;
        for (String parameter : rawQuery.split("&", -1)) {
            int separator = parameter.indexOf('=');
            String name = separator < 0 ? parameter : parameter.substring(0, separator);
            if (!"v".equals(name)) {
                continue;
            }
            if (videoId != null) {
                throw invalid();
            }
            videoId = separator < 0 ? "" : parameter.substring(separator + 1);
        }
        if (videoId == null || !VIDEO_ID_PATTERN.matcher(videoId).matches()) {
            throw invalid();
        }
        return videoId;
    }

    private static String group(Matcher matcher) {
        if (!matcher.matches()) {
            throw invalid();
        }
        return matcher.group(1);
    }

    private static BusinessException invalid() {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED, "지원하지 않는 YouTube 영상 URL입니다.");
    }
}
