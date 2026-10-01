package server.dtn;

import java.util.UUID;

/** 독립 노드의 관리 통신 경계다. AFS 본문과 기준 PVT를 상대 노드로 전송하지 않는다. */
public interface DtnNodeLink {
    boolean sender();

    void validateParticipants(String senderId, String receiverId);

    void register(DtnJob job);

    DtnRemoteResult result(UUID testId);

    void cancel(UUID testId);

    /** 확정된 전송 실패의 미수신 등록만 닫는다. 일반 중지로 대체하지 않는다. */
    default DtnRemoteResult closeWaiting(UUID testId) {
        throw new UnsupportedOperationException("수신 대기 정리 미지원");
    }

    default DtnRemoteResult closeWaiting(UUID testId, String reason) {
        throw new UnsupportedOperationException("조건부 수신 대기 종료 미지원");
    }

    default server.common.DtnModels.ReferenceSnapshot reference(UUID testId) {
        throw new UnsupportedOperationException("비교자료 조회 미지원");
    }
}
