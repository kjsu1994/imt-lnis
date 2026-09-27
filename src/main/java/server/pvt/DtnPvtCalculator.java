package server.pvt;

import server.common.DtnModels.Pvt;

import java.util.List;

/** Local node supplies the existing calculation engine; central does not own native code. */
@FunctionalInterface
public interface DtnPvtCalculator {
    List<Pvt> calculate(List<byte[]> records);
}
