package server.central.dtn;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import server.shared.model.DtnModels.HdtnConfig;
import java.io.IOException;
import java.util.Set;

/** 신규 시험 요청에서만 정수의 묵시적 반올림/문자열 변환을 막는다. 과거 본문 조회에는 적용하지 않는다. */
public class HdtnConfigRequestDeserializer extends JsonDeserializer<HdtnConfig> {
    private static final Set<String> NUMBERS = Set.of(
            "maxNumberOfBundlesInPipeline", "maxSumOfBundleBytesInPipeline", "maxBundleSizeBytes",
            "tcpclMaxSegmentSizeBytes", "neighborDepletedStorageDelaySeconds", "totalStorageCapacityBytes",
            "maxLtpReceiveUdpPacketSizeBytes", "acsSendPeriodMilliseconds");

    @Override
    public HdtnConfig deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        if (!node.isObject()) throw JsonMappingException.from(parser, "hdtnConfig는 객체여야 합니다.");
        for (String key : NUMBERS) {
            if (node.hasNonNull(key) && !node.get(key).isIntegralNumber())
                throw JsonMappingException.from(parser, key + "는 정수여야 합니다.");
        }
        if (node.hasNonNull("enforceBundlePriority") && !node.get("enforceBundlePriority").isBoolean())
            throw JsonMappingException.from(parser, "enforceBundlePriority는 boolean이어야 합니다.");
        return parser.getCodec().treeToValue(node, HdtnConfig.class);
    }
}
