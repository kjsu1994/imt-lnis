package server.node;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import server.common.LnisModels.AgentRole;
import server.common.LnisModels.AgentState;

/** 관리 채널 응답에는 관측 원본, AFS payload, 기준 PVT 또는 인증 토큰을 포함하지 않는다. */
public final class NodeDto {
    private NodeDto() {}

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
        private String gnssTimeState;

        public StatusResponse(int protocolVersion, String agentId, AgentRole role, AgentState state,
                              boolean online, int codecAbiVersion, String baseUrl, Boolean peerOnline) {
            this(protocolVersion, agentId, role, state, online, codecAbiVersion, baseUrl, peerOnline, null);
        }

        public StatusResponse(
                int protocolVersion,
                String agentId,
                AgentRole role,
                AgentState state,
                boolean online,
                int codecAbiVersion,
                String baseUrl) {
            this(protocolVersion, agentId, role, state, online, codecAbiVersion, baseUrl, null);
        }
    }
}
