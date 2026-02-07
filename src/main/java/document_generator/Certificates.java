package document_generator;

import document_generator.Models.CertificateBundle;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public static String exportCertToString(X509Certificate cert) {
        StringWriter sw = new StringWriter();
        try (PemWriter pw = new PemWriter(sw)) {
            PemObject pemObject = new PemObject("CERTIFICATE", cert.getEncoded());
            pw.writeObject(pemObject);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to export certificate", e);
        }
        return sw.toString();
    }

    public static PrivateKey loadPrivateKey(Path filepath) throws Exception {
        String pemContent = Files.readString(filepath, java.nio.charset.StandardCharsets.UTF_8);
        pemContent = pemContent.replaceAll("\r\n", "\n").replaceAll("\r", "\n");

        try (PEMParser pemParser = new PEMParser(new java.io.StringReader(pemContent))) {
            Object object = pemParser.readObject();

            if (object == null) {
                throw new IllegalArgumentException("No valid PEM object found in file: " + filepath);
            }

            JcaPEMKeyConverter converter = new JcaPEMKeyConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME);

            if (object instanceof PEMKeyPair) {
                // Traditional OpenSSL format (like the one we export)
                PEMKeyPair keyPair = (PEMKeyPair) object;
                return converter.getPrivateKey(keyPair.getPrivateKeyInfo());
            } else if (object instanceof PrivateKeyInfo) {
                // PKCS#8 format
                return converter.getPrivateKey((PrivateKeyInfo) object);
            } else {
                throw new IllegalArgumentException(
                    "Unsupported key format: " + object.getClass().getName()
                );
            }
        } catch (Exception e) {
            // If conversion fails, try alternative method
            if (e.getMessage() != null && e.getMessage().contains("unable to convert key pair")) {
                return loadPrivateKeyAlternative(filepath);
            }
            throw e;
        }
    }


    private static PrivateKey loadPrivateKeyAlternative(Path filepath) throws Exception {
        String pemContent = java.nio.file.Files.readString(filepath, java.nio.charset.StandardCharsets.UTF_8);
        pemContent = pemContent.replaceAll("\r\n", "\n").replaceAll("\r", "\n");

        // Remove PEM headers and decode base64
        pemContent = pemContent
            .replace("-----BEGIN EC PRIVATE KEY-----", "")
            .replace("-----END EC PRIVATE KEY-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s+", "");

        byte[] keyBytes = java.util.Base64.getDecoder().decode(pemContent);

        try {
            // Try as PKCS#8 first
            java.security.spec.PKCS8EncodedKeySpec keySpec =
                new java.security.spec.PKCS8EncodedKeySpec(keyBytes);
            java.security.KeyFactory keyFactory =
                java.security.KeyFactory.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            // Parse as SEC1 format and convert to PrivateKeyInfo
            try {
                org.bouncycastle.asn1.ASN1Primitive primitive =
                    org.bouncycastle.asn1.ASN1Primitive.fromByteArray(keyBytes);
                PrivateKeyInfo pkInfo = PrivateKeyInfo.getInstance(primitive);

                JcaPEMKeyConverter converter = new JcaPEMKeyConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME);
                return converter.getPrivateKey(pkInfo);
            } catch (Exception e2) {
                throw new Exception("Failed to load EC private key using alternative method", e2);
            }
        }
    }


    public static X509Certificate loadCertificate(Path filepath) throws Exception {
        String pemContent = Files.readString(filepath, StandardCharsets.UTF_8);
        pemContent = pemContent.replaceAll("\r\n", "\n").replaceAll("\r", "\n");

        try (PEMParser pemParser = new PEMParser(new StringReader(pemContent))) {
            Object object = pemParser.readObject();

            if (object instanceof X509CertificateHolder) {
                X509CertificateHolder certHolder = (X509CertificateHolder) object;
                return new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(certHolder);
            } else {
                throw new IllegalArgumentException(
                    "File does not contain a certificate: " + object.getClass().getName()
                );
            }
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
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        ECGenParameterSpec ecSpec = new ECGenParameterSpec("secp256r1");
        keyPairGenerator.initialize(ecSpec, new SecureRandom());
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        // Subject built with X500NameBuilder (order: C, O, CN)
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.C, countryTag)
            .addRDN(BCStyle.O, "Distributed Provenance Demo " + name)
            .addRDN(BCStyle.CN, "DPD " + name)
            .build();

        X500Name issuer;
        if (authCert != null) {
            X500Name authSubject = new JcaX509CertificateHolder(authCert).getSubject();

            issuer = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.C, authSubject.getRDNs(BCStyle.C)[0].getFirst().getValue())
                .addRDN(BCStyle.O, authSubject.getRDNs(BCStyle.O)[0].getFirst().getValue())
                .addRDN(BCStyle.CN, authSubject.getRDNs(BCStyle.CN)[0].getFirst().getValue())
                .build();
        } else {
            issuer = subject;
        }

        BigInteger serialNumber = new BigInteger(160, new SecureRandom());
        Instant now = Instant.now();
        Date notBefore = Date.from(now);
        Date notAfter = Date.from(now.plus(365 * 10, ChronoUnit.DAYS));

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            issuer,
            serialNumber,
            notBefore,
            notAfter,
            subject,
            keyPair.getPublic()
        );

        BasicConstraints basicConstraints = pathLength != null
            ? new BasicConstraints(pathLength)
            : new BasicConstraints(ca);
        certBuilder.addExtension(
            Extension.basicConstraints,
            true,
            basicConstraints
        );

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

        SubjectKeyIdentifier subjectKeyIdentifier = new SubjectKeyIdentifier(
            keyPair.getPublic().getEncoded()
        );
        certBuilder.addExtension(
            Extension.subjectKeyIdentifier,
            false,
            subjectKeyIdentifier
        );

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
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(signingKey);

        X509CertificateHolder certHolder = certBuilder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certHolder);

        return new CertificateBundle(keyPair.getPrivate(), cert);
    }
}
