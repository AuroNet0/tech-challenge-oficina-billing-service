import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;

public class ClockProbe {
    public static void main(String[] arguments) throws Exception {
        int ahead = 0;
        long minimum = Long.MAX_VALUE;
        long maximum = Long.MIN_VALUE;
        try (var connection = DriverManager.getConnection(arguments[0], "postgres", System.getenv("CLOCK_PROBE_PASSWORD"));
             var statement = connection.prepareStatement("SELECT clock_timestamp()")) {
            for (int index = 0; index < 50; index++) {
                try (var result = statement.executeQuery()) {
                    result.next();
                    Instant database = result.getTimestamp(1).toInstant();
                    Instant application = Instant.now();
                    long difference = Duration.between(application, database).toNanos();
                    if (difference > 0) ahead++;
                    minimum = Math.min(minimum, difference);
                    maximum = Math.max(maximum, difference);
                }
            }
        }
        System.out.printf("{\"samples\":50,\"postgresAfterJavaReceive\":%d,\"minDifferenceNanos\":%d,\"maxDifferenceNanos\":%d}%n",
                ahead, minimum, maximum);
    }
}
