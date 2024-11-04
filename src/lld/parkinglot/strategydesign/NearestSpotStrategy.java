package lld.parkinglot.strategydesign;

import lld.parkinglot.ParkingSpot;
import lld.parkinglot.factorydesign.Vehicle;

import java.util.List;

public class NearestSpotStrategy implements ParkingStrategy {
    @Override
    public ParkingSpot findSpot(Vehicle vehicle, List<ParkingSpot> parkingSpots) {
        // Assume parking spots are sorted by distance
        for (ParkingSpot spot : parkingSpots) {
            if (spot.isAvailable() && spot.getSpotType() == vehicle.getVehicleType()) {
                return spot;
            }
        }
        return null;
    }
}
