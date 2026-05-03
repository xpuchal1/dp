package cz.muni.fi.components_generator.core;

class ProvenanceStorageResponse {
    private Token token;

    public Token getToken() {
        return token;
    }

    public void setToken(Token token) {
        this.token = token;
    }

    public static class Token {

        private Data data;
        private String signature;

        public Data getData() {
            return data;
        }

        public void setData(Data data) {
            this.data = data;
        }

        public String getSignature() {
            return signature;
        }

        public void setSignature(String signature) {
            this.signature = signature;
        }
    }

    public static class Data {

        private String originatorId;
        private String authorityId;
        private long tokenTimestamp;
        private long documentCreationTimestamp;
        private String documentDigest;

        private AdditionalData additionalData;

        public String getOriginatorId() {
            return originatorId;
        }

        public void setOriginatorId(String originatorId) {
            this.originatorId = originatorId;
        }

        public String getAuthorityId() {
            return authorityId;
        }

        public void setAuthorityId(String authorityId) {
            this.authorityId = authorityId;
        }

        public long getTokenTimestamp() {
            return tokenTimestamp;
        }

        public void setTokenTimestamp(long tokenTimestamp) {
            this.tokenTimestamp = tokenTimestamp;
        }

        public long getDocumentCreationTimestamp() {
            return documentCreationTimestamp;
        }

        public void setDocumentCreationTimestamp(long documentCreationTimestamp) {
            this.documentCreationTimestamp = documentCreationTimestamp;
        }

        public String getDocumentDigest() {
            return documentDigest;
        }

        public void setDocumentDigest(String documentDigest) {
            this.documentDigest = documentDigest;
        }

        public AdditionalData getAdditionalData() {
            return additionalData;
        }

        public void setAdditionalData(AdditionalData additionalData) {
            this.additionalData = additionalData;
        }
    }

    public static class AdditionalData {

        private String bundle;
        private String hashFunction;
        private String trustedPartyUri;
        private String trustedPartyCertificate;

        public String getBundle() {
            return bundle;
        }

        public void setBundle(String bundle) {
            this.bundle = bundle;
        }

        public String getHashFunction() {
            return hashFunction;
        }

        public void setHashFunction(String hashFunction) {
            this.hashFunction = hashFunction;
        }

        public String getTrustedPartyUri() {
            return trustedPartyUri;
        }

        public void setTrustedPartyUri(String trustedPartyUri) {
            this.trustedPartyUri = trustedPartyUri;
        }

        public String getTrustedPartyCertificate() {
            return trustedPartyCertificate;
        }

        public void setTrustedPartyCertificate(String trustedPartyCertificate) {
            this.trustedPartyCertificate = trustedPartyCertificate;
        }
    }


}
