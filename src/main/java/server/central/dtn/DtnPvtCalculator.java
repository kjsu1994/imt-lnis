package server.central.dtn;

import java.util.List;
import server.shared.model.DtnModels.Pvt;

/** Local node supplies the existing calculation engine; central does not own native code. */
@FunctionalInterface
public interface DtnPvtCalculator {
  List<Pvt> calculate(List<byte[]> records);
}
