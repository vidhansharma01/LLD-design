package lld.parkinglot.strategydesign;

import lld.parkinglot.ParkingSpot;
import lld.parkinglot.factorydesign.Vehicle;
import java.util.List;


public interface ParkingStrategy {
    public ParkingSpot findSpot(Vehicle vehicle, List<ParkingSpot> parkingSpots);
}
