package server.dtn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeSet;

/** 객체 필드 순서와 공백을 제외한 JSON 동일성 확인용이다. 배열 순서와 값은 그대로 유지한다. */
public final class DtnPayloadDigest {
    private DtnPayloadDigest() {}

    public static String sha256(ObjectMapper mapper, JsonNode value) {
        try {
            byte[] canonical = mapper.writeValueAsBytes(normalize(mapper, value));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception error) {
            throw new IllegalArgumentException("DTN JSON 해시를 계산할 수 없습니다.", error);
        }
    }

    private static JsonNode normalize(ObjectMapper mapper, JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            TreeSet<String> names = new TreeSet<>();
            value.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                result.set(name, normalize(mapper, value.get(name)));
            }
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            for (JsonNode item : value) {
                result.add(normalize(mapper, item));
            }
            return result;
        }
        return value;
    }
}
