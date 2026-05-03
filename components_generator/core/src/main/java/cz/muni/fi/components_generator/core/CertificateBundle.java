package cz.muni.fi.components_generator.core;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

class CertificateBundle {
    private final PrivateKey key;
    private final X509Certificate cert;

    public PrivateKey getKey() {
        return key;
    }

    public X509Certificate getCert() {
        return cert;
    }

    public CertificateBundle(PrivateKey key, X509Certificate cert) {
        this.key = key;
        this.cert = cert;
    }
}