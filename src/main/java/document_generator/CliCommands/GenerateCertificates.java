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
            Certificates.exportKey(ca.getKey(), Path.of("src/main/resources/keys/ca.getKey()"));
            Certificates.exportCert(ca.getCert(), Path.of("src/main/resources/certificates/ca.pem"));

            // Create key, cert for Trusted Party server
            CertificateBundle tp = Certificates.generateCertificate("CZ", "Trusted Party",
                ca.getKey(), ca.getCert(), true, 0);
            Certificates.exportKey(tp.getKey(), Path.of("src/main/resources/keys/tp.getKey()"));
            Certificates.exportCert(tp.getCert(), Path.of("src/main/resources/certificates/tp.pem"));

            // Create key, cert for 2 intermediates
            CertificateBundle int1 = Certificates.generateCertificate("EU", "Intermediate One",
                ca.getKey(), ca.getCert(), true, 2);
            CertificateBundle int2 = Certificates.generateCertificate("CZ", "Intermediate Two",
                int1.getKey(), int1.getCert(), true, 1);

            Certificates.exportKey(int1.getKey(), Path.of("src/main/resources/keys/int1.getKey()"));
            Certificates.exportKey(int2.getKey(), Path.of("src/main/resources/keys/int2.getKey()"));
            Certificates.exportCert(int1.getCert(), Path.of("src/main/resources/certificates/int1.pem"));
            Certificates.exportCert(int2.getCert(), Path.of("src/main/resources/certificates/int2.pem"));

            CertificateBundle organizationKey = Certificates.generateCertificate("EU", "XXXXXXXX",
                int2.getKey(), int2.getCert(), false, null);
            Certificates.exportKey(organizationKey.getKey(), Path.of("src/main/resources/keys/xxxxxxxx.getKey()"));
            Certificates.exportCert(organizationKey.getCert(), Path.of("src/main/resources/certificates/xxxxxxxx.pem"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
}