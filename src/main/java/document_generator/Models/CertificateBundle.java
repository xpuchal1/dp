package document_generator.Models;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

public class CertificateBundle {
    public final PrivateKey key;
    public final X509Certificate cert;

    public CertificateBundle(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
    }
}