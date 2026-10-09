package com.example.Tbooker.dto;

import com.example.Tbooker.entity.ShowSeatStatus;

public record ShowSeatResponse(Long id, Long showId, Long seatId, String seatNumber, ShowSeatStatus status) {
}
