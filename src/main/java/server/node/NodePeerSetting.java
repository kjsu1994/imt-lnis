package server.node;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;

/** 현재 PC의 상대 주소만 저장한다. 관리 토큰은 환경 설정에 남겨 브라우저와 DB 설정 응답에서 제외한다. */
@Entity
@Table(name = "node_peer_setting")
@Data
public class NodePeerSetting {
    @Id private Integer id;
    private String baseUrl;
}
