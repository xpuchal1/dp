package document_generator.CliCommands;

import document_generator.Certificates;
import document_generator.Models.CertificateBundle;
import picocli.CommandLine;

import java.nio.file.Path;

@CommandLine.Command(name = "generate-certificates", description = "")
public class GenerateCertificates implements Runnable {
    @Override
    public void run() {
        try {
            CertificateBundle ca = Certificates.generateCertificate("EU", "Certificate Authority",
                null, null, true, null);
            Certificates.exportKey(ca.key, Path.of("src/main/resources/keys/ca.key"));
            Certificates.exportCert(ca.cert, Path.of("src/main/resources/certificates/ca.pem"));

            // Create key, cert for Trusted Party server
            CertificateBundle tp = Certificates.generateCertificate("CZ", "Trusted Party",
                ca.key, ca.cert, true, 0);
            Certificates.exportKey(tp.key, Path.of("src/main/resources/keys/tp.key"));
            Certificates.exportCert(tp.cert, Path.of("src/main/resources/certificates/tp.pem"));

            // Create key, cert for 2 intermediates
            CertificateBundle int1 = Certificates.generateCertificate("EU", "Intermediate One",
                ca.key, ca.cert, true, 2);
            CertificateBundle int2 = Certificates.generateCertificate("CZ", "Intermediate Two",
                int1.key, int1.cert, true, 1);

            Certificates.exportKey(int1.key, Path.of("src/main/resources/keys/int1.key"));
            Certificates.exportKey(int2.key, Path.of("src/main/resources/keys/int2.key"));
            Certificates.exportCert(int1.cert, Path.of("src/main/resources/certificates/int1.pem"));
            Certificates.exportCert(int2.cert, Path.of("src/main/resources/certificates/int2.pem"));

            CertificateBundle organizationKey = Certificates.generateCertificate("EU", "XXXXXXXX",
                int2.key, int2.cert, false, null);
            Certificates.exportKey(organizationKey.key, Path.of("src/main/resources/keys/xxxxxxxx.key"));
            Certificates.exportCert(organizationKey.cert, Path.of("src/main/resources/certificates/xxxxxxxx.pem"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
}