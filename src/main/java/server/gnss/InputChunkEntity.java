package server.gnss;

import jakarta.persistence.*;

import lombok.*;

import java.io.Serializable;
import java.util.UUID;

/** 단일 GRAW 파일 안에서 업로드 청크가 차지하는 바이트 범위다. */
@Entity
@Table(name = "input_chunks")
@IdClass(InputChunkEntity.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class InputChunkEntity {
    @Id
    @Column(name = "input_id")
    private UUID inputId;

    @Id
    @Column(name = "chunk_index")
    private long chunkIndex;

    @Column(name = "file_offset", nullable = false)
    private long fileOffset;

    @Column(name = "byte_length", nullable = false)
    private int byteLength;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private UUID inputId;
        private long chunkIndex;
    }
}
