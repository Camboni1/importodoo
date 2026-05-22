package bprimport.odoo.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "odoo_connections")
public class OdooConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String database;

    @Column(nullable = false)
    private String login;

    @Column(nullable = false, length = 512)
    private String apiKey;

    /** Cookie de session odoo.sh (optionnel — pour les branches dev.odoo.com protégées) */
    @Column(length = 1024)
    private String platformSessionCookie;

    private boolean active = true;

    private LocalDateTime lastTestedAt;

    private Boolean lastTestSuccess;

    @Column(length = 1000)
    private String lastTestMessage;

    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public String getLogin() { return login; }
    public void setLogin(String login) { this.login = login; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getPlatformSessionCookie() { return platformSessionCookie; }
    public void setPlatformSessionCookie(String platformSessionCookie) { this.platformSessionCookie = platformSessionCookie; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(LocalDateTime lastTestedAt) { this.lastTestedAt = lastTestedAt; }

    public Boolean getLastTestSuccess() { return lastTestSuccess; }
    public void setLastTestSuccess(Boolean lastTestSuccess) { this.lastTestSuccess = lastTestSuccess; }

    public String getLastTestMessage() { return lastTestMessage; }
    public void setLastTestMessage(String lastTestMessage) { this.lastTestMessage = lastTestMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
