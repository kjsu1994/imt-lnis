package server.central.dtn;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;

/** 인증된 콜백의 검증 전 본문. 시험 원본/계산 결과와 분리해 보관한다. */
@Service @RequiredArgsConstructor
public class DtnReceiptService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private server.central.management.DataManagementGuard managementGuard;

    private final DtnReceiptRepository repository;
    private final ObjectMapper mapper;

    @Transactional
    public DtnReceipt capture(byte[] body, String contentType, boolean truncated) {
        return capture(body,contentType,truncated,Instant.now());
    }
    @Transactional
    public DtnReceipt capture(byte[] body, String contentType, boolean truncated, Instant arrivedAt) {
        DtnReceipt receipt=new DtnReceipt();
        receipt.setId(UUID.randomUUID());
        receipt.setArrivedAt(arrivedAt);
        receipt.setBody(body); receipt.setSizeBytes(body.length); receipt.setTruncated(truncated);
        receipt.setContentType(contentType==null?null:contentType.substring(0,Math.min(255,contentType.length())));
        receipt.setStatus("RECEIVED"); receipt.setMessage("본문 저장 완료 · 검증 전");
        if(!truncated) try {
            var node=mapper.readTree(body);
            if(node!=null && node.path("testId").isTextual()) receipt.setTestId(UUID.fromString(node.path("testId").asText()));
        } catch(Exception ignored) { /* 식별 불가능한 원문도 저장한다. */ }
        if(managementGuard!=null) managementGuard.requirePresent("DTN",receipt.getTestId());
        return repository.saveAndFlush(receipt);
    }
    @Transactional
    public void finish(DtnReceipt receipt, String status, String message) {
        receipt.setStatus(status);
        receipt.setMessage(message==null?status:message.substring(0,Math.min(2000,message.length())));
        repository.saveAndFlush(receipt);
    }
    public interface Summary {
        UUID getId(); UUID getTestId(); Instant getArrivedAt();
        String getStatus(); String getMessage(); String getContentType();
        int getSizeBytes(); boolean isTruncated();
    }
    public List<Summary> recent() {
        return repository.findAllByOrderByArrivedAtDesc(PageRequest.of(0,50));
    }
    public DtnReceipt get(UUID id) {
        return repository.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}

@Entity @Table(name="dtn_receipt") @Getter @Setter @NoArgsConstructor
class DtnReceipt {
    @Id private UUID id;
    private UUID testId;
    private Instant arrivedAt;
    private String status;
    @Column(length=2000) private String message;
    private String contentType;
    private int sizeBytes;
    private boolean truncated;
    @Lob @JsonIgnore private byte[] body;
}
interface DtnReceiptRepository extends JpaRepository<DtnReceipt,UUID> {
    List<DtnReceiptService.Summary> findAllByOrderByArrivedAtDesc(org.springframework.data.domain.Pageable pageable);
}
