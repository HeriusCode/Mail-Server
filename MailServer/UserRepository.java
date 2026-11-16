package MailServer;

import java.io.IOException;

/** Internal persistence contract. Hashes must never be exposed by the network layer. */
public interface UserRepository {
    /** Provision account and initial mailbox together; never overwrite an existing account. */
    boolean createAccount(String email, String passwordHash, String welcome) throws IOException;
    String findPasswordHash(String email) throws IOException;
    boolean exists(String email) throws IOException;
}
