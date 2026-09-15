package server.central.dtn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import server.shared.model.DtnModels.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IqReceiverTest {
  @TempDir Path directory;
  private IqMetadata metadata(int week,double tow) {
    var navigation=new ArrayList<IqNavigation>();
    for(int prn:List.of(19,23,24,28)) for(int id=1;id<=3;id++)
      navigation.add(new IqNavigation(prn,List.of(0x8b0000,id<<2,0,0,0,0,0,0,0,0)));
    return new IqMetadata("AFSD",IqReceiver.METHOD,week,tow,"ECEF_CONSTANT_VELOCITY",List.of(19,23,24,28),navigation);
  }
  @Test void validatesOnlySelectedGpsLnavWithCompleteSubframes() {
    var m=metadata(2400,100000); assertDoesNotThrow(()->IqReceiver.validate(m));
    assertThrows(IllegalArgumentException.class,()->IqReceiver.validate(new IqMetadata("AFSD",IqReceiver.METHOD,2400,100000,
        m.trajectory(),m.prns(),m.gpsLnav().subList(1,12))));
    assertThrows(IllegalArgumentException.class,()->IqReceiver.validate(metadata(2400,Double.NaN)));
  }
  @Test void mapsCorrelationEndToSampleStartAndRejectsForeignPrns() throws Exception {
    Path log=directory.resolve("tracking.log");
    Files.writeString(log,"$IQOBS,21.000,2400,19,100020934,0.000130421556,7108.988442,120142.589762,34.844\n");
    var result=IqReceiver.parse(log,metadata(2400,100000));
    assertEquals((.068+.000130421556)*299792458.,result.get(21).get(19)[1],.01);
    Files.writeString(log,"$IQOBS,21.000,2400,1,100020934,0.000130421556,7108,120142,34\n");
    assertThrows(java.io.IOException.class,()->IqReceiver.parse(log,metadata(2400,100000)));
  }
  @Test void acceptsWeekRolloverButNotDuplicateOrNanMeasurements() throws Exception {
    Path log=directory.resolve("tracking.log");
    String line="$IQOBS,2.000,2400,19,934,0.0001,10,20,40\n";
    Files.writeString(log,line);
    assertEquals(.0681*299792458.,IqReceiver.parse(log,metadata(2400,604799)).get(2).get(19)[1],.1);
    Files.writeString(log,line+line);
    assertThrows(java.io.IOException.class,()->IqReceiver.parse(log,metadata(2400,604799)));
    Files.writeString(log,line.replace("0.0001","NaN"));
    assertThrows(java.io.IOException.class,()->IqReceiver.parse(log,metadata(2400,604799)));
  }
  @Test void predictsReferenceAtSampleTimeWithoutChangingReceivedPosition() {
    var m=metadata(2400,604799);
    Pvt initial=new Pvt(); initial.setWeek(2400); initial.setTowSeconds(604799);
    initial.setPositionValid(true); initial.setVelocityValid(true);
    initial.setEcefMeters(new double[]{1,2,3}); initial.setVelocityMetersPerSecond(new double[]{4,5,6});
    initial.setReceiverClockBiasSeconds(0.0);
    Pvt received=new Pvt(); received.setWeek(2401); received.setTowSeconds(1);
    received.setPositionValid(true); received.setVelocityValid(true);
    received.setEcefMeters(new double[]{10,12,15}); received.setVelocityMetersPerSecond(new double[]{4,5,6});
    received.setReceiverClockBiasSeconds(0.0);
    var refs=IqReceiver.references(m,List.of(initial),List.of(received));
    assertArrayEquals(new double[]{9,12,15},refs.getFirst().getEcefMeters());
    assertArrayEquals(new double[]{10,12,15},received.getEcefMeters());
    var comparison=IqReceiver.comparison(refs,List.of(received));
    assertEquals("MEASURED",comparison.get("verdict"));
    assertFalse(comparison.containsKey("positionToleranceMeters"));
    assertEquals("INCONCLUSIVE",IqReceiver.comparison(List.of(),List.of(received)).get("verdict"));
  }
  @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void exportsNavigationNotSenderRawObservations() throws Exception {
    var records=server.shared.codec.GrawCodec.splitLengthPrefixed(server.agent.codec.NativePvtIntegrationTest.validSample());
    try(var calculator=new server.shared.codec.NativePvtCodec(Path.of(System.getProperty("lnis.native.candidate","native/bin/win-x64")))) {
      var pvt=calculator.calculate(records).getFirst();
      var source=IqReceiver.source(IqService.earthInput(records,pvt),"A".repeat(64));
      assertEquals(15,source.metadata().gpsLnav().size());
      assertEquals(List.of(19,23,24,28,29),source.metadata().prns());
      assertArrayEquals(pvt.getEcefMeters(),source.reference().getEcefMeters());
      assertEquals(10,source.metadata().gpsLnav().getFirst().words24().size());
    }
  }
}
