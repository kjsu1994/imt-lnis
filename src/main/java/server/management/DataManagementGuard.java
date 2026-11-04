package server.management;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
@RequiredArgsConstructor
public class DataManagementGuard {
    private final EntityManager em;
    public final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);

    private volatile boolean resetting;
    private volatile String revision;
    private volatile String storageOwner;

    @jakarta.annotation.PostConstruct
    void restoreRevision() {
        var entry = em.find(ManagementEntry.class, "RESET_REVISION");
        revision = entry == null ? null : entry.getValue().replace("\"", "");
    }

    public String storageOwner() { return storageOwner; }
    void storageOwner(String value) { storageOwner = value; }
    public boolean resetting() { return resetting; }
    void resetting(boolean value) { resetting = value; }
    public String revision() { return revision; }
    void revision(String value) { revision = value; }

    public void requireOperational() {
        if (resetting)
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "초기화 처리 중입니다. 데이터 관리에서 진행 상태를 확인하세요.");
    }

    public boolean deleted(String type, UUID id) {
        return id != null && em.find(ManagementEntry.class, "DELETED:" + type + ":" + id) != null;
    }

    public void requireUnpinned(String type, UUID id) {
        if (em.find(ManagementEntry.class, "PIN:" + type + ":" + id) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "보관 고정된 자료입니다.");
        }
        if (type.equals("INPUT") || type.equals("IQ")) {
            String field = type.equals("INPUT") ? "inputId" : "iqFileId";
            for (UUID trial :
                    em.createQuery(
                                    "select e.id from DtnJob e where e." + field + "=:id",
                                    UUID.class)
                            .setParameter("id", id)
                            .getResultList()) {
                requireUnpinned("DTN", trial);
            }
        }
    }

    public void requirePresent(String type, UUID id) {
        if (deleted(type, id)) {
            throw new ResponseStatusException(HttpStatus.GONE, "관리자가 삭제한 자료입니다.");
        }
    }
}
