import json
import os


class Config:

    def __init__(self, config_path):
        self.fqdn = ""
        self.cert = ""
        self.private_key = ""
        self.id = ""
        self.trusted_certs = []

        self._load_config(config_path)

    def _load_config(self, config_path):
        with open(config_path, 'rb') as f:
            config = json.load(f)

        required_config_fields = ("id", "fqdn", "publicCertPath", "privateKeyPath", "trustedCertsDirPath")
        for field in required_config_fields:
            assert field in config, f"{field} missing in config!"

        self.fqdn = config['fqdn']
        if self.fqdn[-1] == '/':
            self.fqdn = self.fqdn[:-1]

        assert os.path.isfile(config['publicCertPath']), f"Public cert not found under given path: {config['publicCertPath']}"
        with open(config['publicCertPath'], 'r') as f:
            self.cert = f.read()

        assert os.path.isfile(config['privateKeyPath']), f"PK not found under given path"
        with open(config['privateKeyPath'], 'rb') as f:
            self.private_key = f.read()

        assert os.path.isdir(config['trustedCertsDirPath']), f"Trusted certs not found under given path"

        dir = config['trustedCertsDirPath']
        if dir[-1] != '/':
            dir = dir + '/'
        for file in os.listdir(dir):
            with open(dir + file, 'r') as f:
                content = f.read()
                self.trusted_certs.append(content)

        self.id = config['id']
