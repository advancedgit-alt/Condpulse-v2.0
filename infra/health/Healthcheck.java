import java.net.HttpURLConnection;
import java.net.URI;

/** Probe local, sem credenciais, corpo de resposta ou mensagens nos logs. */
public final class Healthcheck {
    public static void main(String[] args) {
        HttpURLConnection connection = null;
        int result = 1;
        try {
            connection = (HttpURLConnection) URI.create(
                "http://127.0.0.1:8080/actuator/health/readiness").toURL().openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() == 200) result = 0;
        } catch (Exception ignored) {
            // Saída não zero informa indisponibilidade sem expor detalhes.
        } finally {
            if (connection != null) connection.disconnect();
        }
        System.exit(result);
    }
}
