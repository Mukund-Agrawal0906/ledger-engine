package com.mukundagrawal.ledger.stress;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Writes a markdown report to {@code target/} (picked up by the stress CI workflow) and stdout. */
public final class StressReport {

	private StressReport() {
	}

	public static void write(String fileName, String markdown) {
		System.out.println(markdown);
		try {
			Files.writeString(Path.of("target", fileName), markdown);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Nearest-rank percentile of latencies in nanoseconds, returned in milliseconds. */
	public static double percentileMs(long[] nanos, double p) {
		long[] sorted = nanos.clone();
		Arrays.sort(sorted);
		int rank = (int) Math.ceil(p / 100.0 * sorted.length);
		return sorted[Math.max(0, rank - 1)] / 1_000_000.0;
	}

	public static String environment() {
		return "JDK " + Runtime.version() + ", " + Runtime.getRuntime().availableProcessors() + " CPUs visible, "
				+ System.getProperty("os.name") + " " + System.getProperty("os.arch");
	}

}
