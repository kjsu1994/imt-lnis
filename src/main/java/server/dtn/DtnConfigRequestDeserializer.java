package server.dtn;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;

import server.common.DtnModels.DtnConfig;

import java.io.IOException;
import java.util.Set;

/** 신규 시험 요청에서 정수의 묵시적 반올림·문자열 변환을 막는다. 범위 제한은 두지 않는다. */
public class DtnConfigRequestDeserializer extends JsonDeserializer<DtnConfig> {
    private static final Set<String> NUMBERS =
            Set.of(
                    "sdrHeapSizeBytes",
                    "sdrWorkingMemorySizeBytes",
                    "contactRateBytesPerSec",
                    "maxProductionRateBytesPerSec",
                    "maxConsumptionRateBytesPerSec",
                    "maxBundleSizeBytes",
                    "tcpclMaxSegmentSizeBytes",
                    "stcpMaxSegmentSizeBytes");

    @Override
    public DtnConfig deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        if (!node.isObject()) {
            throw JsonMappingException.from(parser, "dtnConfig는 객체여야 합니다.");
        }
        for (String key : NUMBERS) {
            if (node.hasNonNull(key) && !node.get(key).isIntegralNumber()) {
                throw JsonMappingException.from(parser, key + "는 정수여야 합니다.");
            }
        }
        if (node.hasNonNull("sdrTransientMode") && !node.get("sdrTransientMode").isBoolean()) {
            throw JsonMappingException.from(parser, "sdrTransientMode는 boolean이어야 합니다.");
        }
        if (node.hasNonNull("routingMode") && !node.get("routingMode").isTextual()) {
            throw JsonMappingException.from(parser, "routingMode는 문자열이어야 합니다.");
        }
        return parser.getCodec().treeToValue(node, DtnConfig.class);
    }
}
