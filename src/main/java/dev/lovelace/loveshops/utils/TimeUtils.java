package dev.lovelace.loveshops.utils;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;

public final class TimeUtils {

    private TimeUtils() {}

    public static boolean isSellerTimeWindow(String arrivalDay, String arrivalTime, String departureTime) {
        LocalDateTime now = LocalDateTime.now();
        DayOfWeek targetDay = DayOfWeek.valueOf(arrivalDay.toUpperCase());
        
        if (now.getDayOfWeek() != targetDay) {
            return false;
        }

        LocalTime start = LocalTime.parse(arrivalTime, DateTimeFormatter.ofPattern("HH:mm"));
        LocalTime end = LocalTime.parse(departureTime, DateTimeFormatter.ofPattern("HH:mm"));

        LocalTime currentTime = now.toLocalTime();
        return !currentTime.isBefore(start) && !currentTime.isAfter(end);
    }

    public static String getNextArrivalText(String arrivalDay, String arrivalTime) {
        LocalDateTime now = LocalDateTime.now();
        DayOfWeek targetDay = DayOfWeek.valueOf(arrivalDay.toUpperCase());
        LocalTime start = LocalTime.parse(arrivalTime, DateTimeFormatter.ofPattern("HH:mm"));

        if (now.getDayOfWeek() == targetDay && now.toLocalTime().isBefore(start)) {
            return "Сегодня в " + arrivalTime;
        }

        LocalDateTime nextArrival = now.with(TemporalAdjusters.next(targetDay)).with(start);
        if (now.plusDays(1).getDayOfWeek() == targetDay) {
            return "Завтра в " + arrivalTime;
        }
        return nextArrival.format(DateTimeFormatter.ofPattern("dd.MM в HH:mm"));
    }

    public static String formatRemainingTime(long secondsRemaining) {
        if (secondsRemaining <= 0) return "Завершено";
        long hours = secondsRemaining / 3600;
        long minutes = (secondsRemaining % 3600) / 60;
        long secs = secondsRemaining % 60;

        if (hours > 0) {
            return String.format("%dч %02dм", hours, minutes);
        } else if (minutes > 0) {
            return String.format("%dм %02dс", minutes, secs);
        } else {
            return String.format("%dс", secs);
        }
    }

    public static boolean isScheduleTimeWindow(java.util.List<String> days, String arrivalTime, String departureTime) {
        if (days == null || days.isEmpty()) return false;
        LocalDateTime now = LocalDateTime.now();
        DayOfWeek currentDay = now.getDayOfWeek();

        boolean dayMatches = false;
        for (String day : days) {
            try {
                if (DayOfWeek.valueOf(day.toUpperCase()) == currentDay) {
                    dayMatches = true;
                    break;
                }
            } catch (IllegalArgumentException ignored) {}
        }

        if (!dayMatches) return false;

        LocalTime start = LocalTime.parse(arrivalTime, DateTimeFormatter.ofPattern("HH:mm"));
        LocalTime end = LocalTime.parse(departureTime, DateTimeFormatter.ofPattern("HH:mm"));

        LocalTime currentTime = now.toLocalTime();
        return !currentTime.isBefore(start) && !currentTime.isAfter(end);
    }

    public static String getNextScheduleArrivalText(java.util.List<String> days, String arrivalTime) {
        if (days == null || days.isEmpty()) return "Неизвестно";
        LocalDateTime now = LocalDateTime.now();
        LocalTime start = LocalTime.parse(arrivalTime, DateTimeFormatter.ofPattern("HH:mm"));

        // Check if today matches and start is in future
        for (String day : days) {
            try {
                DayOfWeek d = DayOfWeek.valueOf(day.toUpperCase());
                if (now.getDayOfWeek() == d && now.toLocalTime().isBefore(start)) {
                    return "Сегодня в " + arrivalTime;
                }
            } catch (IllegalArgumentException ignored) {}
        }

        // Find nearest future day
        LocalDateTime nearest = null;
        for (String day : days) {
            try {
                DayOfWeek d = DayOfWeek.valueOf(day.toUpperCase());
                LocalDateTime next = now.with(TemporalAdjusters.next(d)).with(start);
                if (nearest == null || next.isBefore(nearest)) {
                    nearest = next;
                }
            } catch (IllegalArgumentException ignored) {}
        }

        if (nearest == null) return "Скоро";
        if (nearest.toLocalDate().equals(now.toLocalDate().plusDays(1))) {
            return "Завтра в " + arrivalTime;
        }
        return nearest.format(DateTimeFormatter.ofPattern("dd.MM в HH:mm"));
    }
}
