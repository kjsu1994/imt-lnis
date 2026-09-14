package server.central.node;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 화면에는 토큰 값이 아니라 설정 여부만 반환한다. */
public final class NodeConnectionDto {
    private NodeConnectionDto() {}

    @Data
    @NoArgsConstructor
    public static class AddressRequest {
        @NotBlank
        private String ip;
        @Min(1)
        @Max(65535)
        private int port;
        @Pattern(regexp = "https?")
        private String scheme = "http";
    }

    @Data
    public static class Configuration {
        private String ip;
        private int port;
        private String scheme;
        private String baseUrl;
        private String peerAgentId;
        private boolean tokenConfigured;
        private boolean editable;
        private boolean busy;
        private boolean peerOnline;
        private Boolean reverseOnline;
    }

    @Data
    public static class ProbeResult {
        private boolean connected;
        private boolean ready;
        private long elapsedMilliseconds;
        private String message;
        private NodeDto.StatusResponse node;
    }
}
