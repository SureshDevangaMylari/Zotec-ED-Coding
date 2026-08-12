package com.wl.util;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;

public class DateUtil {

	public static String next_BusinessDay(int days) {
		LocalDate date = LocalDate.now();
		int add = days;
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yy");
		LocalDate next6day = LocalDate.now();
		while (add > 0) {
			next6day = next6day.plusDays(1);

			if (next6day.getDayOfWeek().toString().equals("SATURDAY")) {
				next6day = next6day.plusDays(2);
			} else

			if (next6day.getDayOfWeek().toString().equals("SUNDAY")) {
				next6day = next6day.plusDays(1);

			}

			add--;

		}

		String nextDate = formatter.format(next6day);
		System.out.println(nextDate);
		return nextDate;
	}

	public static String next_BusinessDay(String oldDate, int days) {

		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");
//		cal.set(Integer.valueOf(year), Integer.valueOf(month), Integer.valueOf(date));
		SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy");
		String formattedDate = "";
		System.out.println("old date" + oldDate);
		LocalDate Edate = LocalDate.parse(oldDate, formatter);
		formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy");
		int add = days;
		LocalDate next6day = Edate;
		while (add > 0) {
			next6day = next6day.plusDays(1);

			if (next6day.getDayOfWeek().toString().equals("SATURDAY")) {
				next6day = next6day.plusDays(2);
			} else

			if (next6day.getDayOfWeek().toString().equals("SUNDAY")) {
				next6day = next6day.plusDays(1);

			}

			add--;

		}
		formattedDate = formatter.format(next6day);
		System.out.println("  === " + formattedDate);
		return formattedDate;
	}
}
