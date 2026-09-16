package server.central.dtn;

import java.util.UUID;

/** 독립 노드의 관리 통신 경계다. AFS 본문과 기준 PVT를 상대 노드로 전송하지 않는다. */
public interface DtnNodeLink {
    boolean sender();

    void validateParticipants(String senderId, String receiverId);

    void register(DtnJob job);

    DtnRemoteResult result(UUID testId);

    void cancel(UUID testId);
}
