package server.central.dtn;

import java.net.URI;
import java.net.URISyntaxException;

/** 사용자가 선택한 REST 주소를 검증한다. URL에 인증 정보를 넣거나 redirect로 우회하지 않는다. */
public final class DtnDestination {
    private DtnDestination()
    {
    }

    public static URI resolve(String requestedUrl, String defaultUrl)
    {
        String value = requestedUrl == null || requestedUrl.isBlank() ? defaultUrl : requestedUrl;
        if (value == null || value.isBlank() || value.length() > 2048) {
            throw new IllegalArgumentException("DTN/HDTN 어댑터의 서버 주소를 입력하세요. 최대 2048자입니다.");
        }
        URI uri;
        try {
            uri = URI.create(value.trim()).normalize();
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("올바른 DTN/HDTN 서버 주소를 입력하세요.");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("http(s)://호스트:포트 형식을 사용하세요. URL 내 인증 정보와 #fragment는 허용하지 않습니다.");
        }
        String path = uri.getPath();
        if (path != null && !path.isBlank() && !"/".equals(path)) {
            return uri;
        }
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    "/transfers", uri.getQuery(), null);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("DTN/HDTN 어댑터 전송 URL을 만들 수 없습니다.", error);
        }
    }

    public static boolean usesConfiguredToken(URI destination, String defaultUrl)
    {
        try {
            // 다른 주소로 기존 비밀 토큰을 자동 전달하지 않는다. 정규화된 전송 URL 전체가 일치해야 한다.
            return destination.equals(resolve(null, defaultUrl));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }
}
