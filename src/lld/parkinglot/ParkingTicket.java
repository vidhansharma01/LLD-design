package lld.parkinglot;

import lld.parkinglot.factorydesign.Vehicle;

import java.util.Date;

class ParkingTicket {
    String ticketId;
    Date issuedTime;
    ParkingSpot parkingSpot;
    Vehicle vehicle;

    public ParkingTicket(String ticketId, Vehicle vehicle, ParkingSpot parkingSpot) {
        this.ticketId = ticketId;
        this.vehicle = vehicle;
        this.parkingSpot = parkingSpot;
        this.issuedTime = new Date();
    }

    public String getTicketId() {
        return ticketId;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public ParkingSpot getParkingSpot() {
        return parkingSpot;
    }

    public Date getIssuedTime() {
        return issuedTime;
    }
}
