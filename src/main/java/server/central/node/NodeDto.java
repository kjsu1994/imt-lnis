package server.central.node;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import server.shared.model.LnisModels.AgentRole;
import server.shared.model.LnisModels.AgentState;

/** 관리 채널 응답에는 관측 원본, AFS payload, 기준 PVT 또는 인증 토큰을 포함하지 않는다. */
public final class NodeDto {
    private NodeDto()
    {
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusResponse {
        private int protocolVersion;
        private String agentId;
        private AgentRole role;
        private AgentState state;
        private boolean online;
        private int codecAbiVersion;
        private String baseUrl;
        private Boolean peerOnline;
        public StatusResponse(int protocolVersion, String agentId, AgentRole role, AgentState state,
                boolean online, int codecAbiVersion, String baseUrl) {
            this(protocolVersion, agentId, role, state, online, codecAbiVersion, baseUrl, null);
        }
    }
}
