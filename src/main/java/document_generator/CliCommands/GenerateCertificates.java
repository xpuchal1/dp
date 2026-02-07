package document_generator.CliCommands;

package document_generator;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

public class Certificates {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public static class CertificateBundle {
        public final PrivateKey key;
        public final X509Certificate cert;

        public CertificateBundle(PrivateKey key, X509Certificate cert) {
            this.key = key;
            this.cert = cert;
        }
    }

    public static void exportKey(PrivateKey key, Path filepath) throws IOException {
        Files.createDirectories(filepath.getParent());

        try (PemWriter pemWriter = new PemWriter(new FileWriter(filepath.toFile()))) {
            PemObject pemObject = new PemObject("EC PRIVATE KEY", key.getEncoded());
            pemWriter.writeObject(pemObject);
        }
    }

    public static void exportCert(X509Certificate cert, Path filepath) throws IOException {
        Files.createDirectories(filepath.getParent());

        try (PemWriter pemWriter = new PemWriter(new FileWriter(filepath.toFile()))) {
            PemObject pemObject = new PemObject("CERTIFICATE", cert.getEncoded());
            pemWriter.writeObject(pemObject);
        } catch (Exception e) {
            throw new IOException("Failed to export certificate", e);
        }
    }

    public static CertificateBundle generateCertificate(
        String countryTag,
        String name,
        PrivateKey authKey,
        X509Certificate authCert,
        boolean ca,
        Integer pathLength) throws Exception {

        // Generate EC key pair using SECP256R1 (equivalent to Python's ec.SECP256R1())
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC", "BC");
        ECGenParameterSpec ecSpec = new ECGenParameterSpec("secp256r1");
        keyPairGenerator.initialize(ecSpec, new SecureRandom());
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        // Build subject name
        X500Name subject = new X500Name(String.format(
            "C=%s,O=Distributed Provenance Demo %s,CN=DPD %s",
            countryTag, name, name
        ));

        // Determine issuer (either from auth cert or self-signed)
        X500Name issuer = authCert != null
            ? new X500Name(authCert.getSubjectX500Principal().getName())
            : subject;

        // Generate serial number
        BigInteger serialNumber = new BigInteger(160, new SecureRandom());

        // Set validity period (10 years)
        Instant now = Instant.now();
        Date notBefore = Date.from(now);
        Date notAfter = Date.from(now.plus(365 * 10, ChronoUnit.DAYS));

        // Build certificate
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            issuer,
            serialNumber,
            notBefore,
            notAfter,
            subject,
            keyPair.getPublic()
        );

        // Add BasicConstraints extension
        BasicConstraints basicConstraints = pathLength != null
            ? new BasicConstraints(pathLength)
            : new BasicConstraints(ca);
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            basicConstraints
        );

        // Add KeyUsage extension
        int keyUsage = KeyUsage.digitalSignature | KeyUsage.cRLSign;
        if (ca) {
            keyUsage |= KeyUsage.keyCertSign;
        } else {
            keyUsage |= KeyUsage.keyEncipherment;
        }
        certBuilder.addExtension(
            Extension.keyUsage,
            true,
            new KeyUsage(keyUsage)
        );

        // Add SubjectKeyIdentifier extension
        SubjectKeyIdentifier subjectKeyIdentifier = new SubjectKeyIdentifier(
            keyPair.getPublic().getEncoded()
        );
        certBuilder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            subjectKeyIdentifier
        );

        // Add ExtendedKeyUsage for non-CA certificates
        if (!ca) {
            KeyPurposeId[] purposes = {
                KeyPurposeId.id_kp_clientAuth,
                KeyPurposeId.id_kp_serverAuth
            };
            certBuilder.addExtension(
                Extension.extendedKeyUsage,
                false,
                new ExtendedKeyUsage(purposes)
            );
        }

        // Add AuthorityKeyIdentifier if signed by another cert
        if (authCert != null) {
            byte[] authSubjectKeyId = authCert.getExtensionValue(
                Extension.subjectKeyIdentifier.getId()
            );
            if (authSubjectKeyId != null) {
                // Parse the extension value (it's wrapped in an OCTET STRING)
                SubjectKeyIdentifier authSKI = SubjectKeyIdentifier.getInstance(
                    org.bouncycastle.asn1.ASN1OctetString.getInstance(authSubjectKeyId).getOctets()
                );
                AuthorityKeyIdentifier authorityKeyIdentifier =
                    new AuthorityKeyIdentifier(authSKI.getKeyIdentifier());
                certBuilder.addExtension(
                    Extension.authorityKeyIdentifier,
                    false,
                    authorityKeyIdentifier
                );
            }
        }

        // Sign the certificate
        PrivateKey signingKey = authKey != null ? authKey : keyPair.getPrivate();
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
            .setProvider("BC")
            .build(signingKey);

        X509CertificateHolder certHolder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder);

        return new CertificateBundle(keyPair.getPrivate(), cert);
    }

    public static void main(String[] args) throws Exception {
        // Create key, cert for certificate authority
        CertificateBundle ca = generateCertificate("EU", "Certificate Authority",
            null, null, true, null);
        exportKey(ca.key, Paths.get("./dbprov_trusted_party/config/certificates/trusted_keys/ca.key"));
        exportCert(ca.cert, Paths.get("./dbprov_trusted_party/config/certificates/trusted_certs/ca.pem"));

        // Create key, cert for Trusted Party server
        CertificateBundle tp = generateCertificate("CZ", "Trusted Party",
            ca.key, ca.cert, true, 0);
        exportKey(tp.key, Paths.get("./dbprov_trusted_party/config/certificates/tp.key"));
        exportCert(tp.cert, Paths.get("./dbprov_trusted_party/config/certificates/tp.pem"));

        // Create key, cert for 2 intermediates
        CertificateBundle int1 = generateCertificate("EU", "Intermediate One",
            ca.key, ca.cert, true, 2);
        CertificateBundle int2 = generateCertificate("CZ", "Intermediate Two",
            int1.key, int1.cert, true, 1);

        exportKey(int1.key, Paths.get("./dbprov_provenance/resources/keys/int1.key"));
        exportKey(int2.key, Paths.get("./dbprov_provenance/resources/keys/int2.key"));
        exportCert(int1.cert, Paths.get("./dbprov_provenance/resources/certificates/int1.pem"));
        exportCert(int2.cert, Paths.get("./dbprov_provenance/resources/certificates/int2.pem"));

        System.out.println("Certificate generation completed successfully!");
    }
}