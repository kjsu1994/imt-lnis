package server.central.management;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component @RequiredArgsConstructor
public class DataManagementGuard {
    private final EntityManager em;
    public final ReentrantReadWriteLock gate=new ReentrantReadWriteLock(true);
    public boolean deleted(String type,UUID id) {
        return id!=null && em.find(ManagementEntry.class,"DELETED:"+type+":"+id)!=null;
    }
    public void requireUnpinned(String type,UUID id) {
        if(em.find(ManagementEntry.class,"PIN:"+type+":"+id)!=null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"보관 고정된 자료입니다.");
        if(type.equals("INPUT") || type.equals("IQ")) {
            String field=type.equals("INPUT")?"inputId":"iqFileId";
            for(UUID trial:em.createQuery("select e.id from DtnJob e where e."+field+"=:id",UUID.class).setParameter("id",id).getResultList())
                requireUnpinned("DTN",trial);
            if(type.equals("INPUT")) for(UUID trial:em.createQuery("select e.sessionId from TestSessionEntity e where e.inputId=:id",UUID.class).setParameter("id",id).getResultList())
                requireUnpinned("AFS",trial);
        }
    }
    public void requirePresent(String type,UUID id) {
        if(deleted(type,id)) throw new ResponseStatusException(HttpStatus.GONE,"관리자가 삭제한 자료입니다.");
    }
}
