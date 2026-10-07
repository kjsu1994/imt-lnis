package server.pvt;

import server.common.DtnModels.Pvt;

import java.util.List;

/** Local node supplies the existing calculation engine; central does not own native code. */
public interface DtnPvtCalculator {
    default List<Pvt> calculate(List<byte[]> records) {
        return calculate(records, PvtConstellation.GPS);
    }

    List<Pvt> calculate(List<byte[]> records, PvtConstellation constellation);
}
