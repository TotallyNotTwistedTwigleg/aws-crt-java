/**
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0.
 */
package software.amazon.awssdk.crt.io;

import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.function.Consumer;

import software.amazon.awssdk.crt.CrtResource;
import software.amazon.awssdk.crt.CrtRuntimeException;
import software.amazon.awssdk.crt.utils.PemUtils;
import software.amazon.awssdk.crt.utils.StringUtils;

/**
 * This class wraps the aws_tls_connection_options from aws-c-io to provide
 * access to TLS configuration contexts in the AWS Common Runtime.
 */
public final class TlsContextOptions extends CrtResource {

    public enum TlsVersions {
        /**
         * SSL v3. This should almost never be used.
         */
        SSLv3(0),
        TLSv1(1),
        /**
         * TLS 1.1
         */
        TLSv1_1(2),
        /**
         * TLS 1.2
         */
        TLSv1_2(3),
        /**
         * TLS 1.3
         */
        TLSv1_3(4),
        /**
         * Use whatever the system default is. This is usually the best option, as it will be automatically updated
         * as the underlying OS or platform changes.
         */
        TLS_VER_SYS_DEFAULTS(128);

        private int version;
        TlsVersions(int val) {
            version = val;
        }

        int getValue() { return version; }
    }

    /**
     * Sets the minimum acceptable TLS version that the {@link TlsContext} will
     * allow. Not compatible with setCipherPreference() API.
     *
     * Select from TlsVersions, a good default is TlsVersions.TLS_VER_SYS_DEFAULTS
     * as this will update if the OS TLS is updated
     */
    public TlsVersions minTlsVersion = TlsVersions.TLS_VER_SYS_DEFAULTS;
    /**
     * Sets the TLS Cipher Preferences that can be negotiated and used during the
     * TLS Connection. Not compatible with setMinimumTlsVersion() API.
     *
     */
    public TlsCipherPreference tlsCipherPreference = TlsCipherPreference.TLS_CIPHER_SYSTEM_DEFAULT;
    /**
     * Sets the ALPN protocol list that will be provided when a TLS connection
     * starts e.g. "x-amzn-mqtt-ca"
     */
    public List<String> alpnList = new ArrayList<>();
    /**
     * Set whether or not the peer should be verified. Default is true for clients,
     * and false for servers. If you are in a development or debugging environment,
     * you can disable this to avoid or diagnose trust store issues. This should
     * always be true on clients in the wild. If you set this to true on a server,
     * it will validate every client connection.
     */
    public boolean verifyPeer = false;

    private String certificate;
    private String privateKey;
    private String certificatePath;
    private String privateKeyPath;
    private String caRoot;
    private String caFile;
    private String caDir;
    private String pkcs12Path;
    private String pkcs12Password;
    private TlsContextPkcs11Options pkcs11Options;
    private TlsContextCustomKeyOperationOptions customKeyOperations;
    private String windowsCertStorePath;

    /**
     * Creates a new set of options that can be used to create a {@link TlsContext}
     */
    private TlsContextOptions() {

    }

    @Override
    public long getNativeHandle() {
        if (super.getNativeHandle() == 0) {
            if (tlsCipherPreference != TlsCipherPreference.TLS_CIPHER_SYSTEM_DEFAULT
                    && minTlsVersion != TlsVersions.TLS_VER_SYS_DEFAULTS) {
                throw new IllegalStateException("tlsCipherPreference and minTlsVersion are mutually exclusive");
            }
            acquireNativeHandle(tlsContextOptionsNew(
                minTlsVersion.getValue(),
                tlsCipherPreference.getValue(),
                alpnList.size() > 0 ? StringUtils.join(";", alpnList) : null,
                certificate,
                privateKey,
                certificatePath,
                privateKeyPath,
                caRoot,
                caFile,
                caDir,
                verifyPeer,
                pkcs12Path,
                pkcs12Password,
                pkcs11Options,
                customKeyOperations,
                windowsCertStorePath
            ));
        }
        return super.getNativeHandle();
    }

    /**
     * Determines whether a resource releases its dependencies at the same time the native handle is released or if it waits.
     * Resources that wait are responsible for calling releaseReferences() manually.
     */
    @Override
    protected boolean canReleaseReferencesImmediately() { return true; }

    /**
     * Frees the native resources associated with this instance
     */
    @Override
    protected void releaseNativeHandle() {
        // It is perfectly acceptable for this to have never created a native resource
        if (!isNull()) {
            tlsContextOptionsDestroy(getNativeHandle());
        }
    }

    /**
     * Sets the TLS cipher preferences to use in contexts using this configuration
     * @param cipherPref cipher preferences to use
     */
    public void setCipherPreference(TlsCipherPreference cipherPref) {
        if(!isCipherPreferenceSupported(cipherPref)) {
            throw new IllegalArgumentException("TlsCipherPreference is not supported on this platform: " + cipherPref.name());
        }

        if (this.minTlsVersion != TlsVersions.TLS_VER_SYS_DEFAULTS && cipherPref != TlsCipherPreference.TLS_CIPHER_SYSTEM_DEFAULT) {
            throw new IllegalArgumentException("Currently only setMinimumTlsVersion() or setCipherPreference() may be used, not both.");
        }

        this.tlsCipherPreference = cipherPref;
    }

    /**
     * Sets the path to the certificate that identifies this mutual TLS (mTLS) host. Must be in PEM format.
     * @param certificatePath Path to PEM format certificate
     * @param privateKeyPath Path to PEM format private key
     */
    public void initMtlsFromPath(String certificatePath, String privateKeyPath) {
        this.certificatePath = certificatePath;
        this.privateKeyPath = privateKeyPath;
    }

    /**
     * Sets the certificate/key pair that identifies this mutual TLS (mTLS) host. Must be in
     * PEM format.
     *
     * @param certificate PEM armored certificate
     * @param privateKey  PEM armored private key
     * @throws IllegalArgumentException If the certificate or privateKey are not in PEM format or if they contain chains
     */
    public void initMtls(String certificate, String privateKey) throws IllegalArgumentException {
        this.certificate = PemUtils.cleanUpPem(certificate);
        PemUtils.sanityCheck(certificate, 1, "CERTIFICATE");

        this.privateKey = PemUtils.cleanUpPem(privateKey);
        PemUtils.sanityCheck(privateKey, 1, "PRIVATE KEY");
    }

    /**
     * Apple platforms only - Initializes mutual TLS (mTLS) with PKCS12 file and password
     * @param pkcs12Path Path to PKCS12 file
     * @param pkcs12Password PKCS12 password
     */
    public void initMtlsPkcs12(String pkcs12Path, String pkcs12Password) {
        if (this.certificate != null || this.privateKey != null || this.certificatePath != null
                || this.privateKeyPath != null) {
            throw new IllegalArgumentException(
                    "PKCS#12 and mTLS via certificate/private key pair are mutually exclusive");
        }
        this.pkcs12Path = pkcs12Path;
        this.pkcs12Password = pkcs12Password;
    }

    /**
     * Returns whether or not ALPN is supported on the current platform
     * @return true if ALPN is supported, false otherwise
     */
    public static boolean isAlpnSupported() {
        return tlsContextOptionsIsAlpnAvailable();
    }

    /**
     * Returns whether or not the current platform can be configured to a specific TlsCipherPreference.
     * @param cipherPref The TlsCipherPreference to check
     * @return True if the current platform does support this TlsCipherPreference, false otherwise
     */
    public static boolean isCipherPreferenceSupported(TlsCipherPreference cipherPref) {
        return tlsContextOptionsIsCipherPreferenceSupported(cipherPref.getValue());
    }

    /**
     * Helper function to provide a TlsContext-local trust store
     * @param caPath Path to the local trust store. Can be null.
     * @param caFile Path to the root certificate. Must be in PEM format.
     */
    public void overrideDefaultTrustStoreFromPath(String caPath, String caFile) {
        if (this.caRoot != null) {
            throw new IllegalArgumentException("Certificate authority is already specified via PEM buffer");
        }
        this.caDir = caPath;
        this.caFile = caFile;
    }

    /**
     * Helper function to provide a TlsContext-local trust store
     *
     * @param caRoot Buffer containing the root certificate chain. Must be in PEM format.
     * @throws IllegalArgumentException if the CA Root PEM file is malformed
     */
    public void overrideDefaultTrustStore(String caRoot) throws IllegalArgumentException {
        if (this.caFile != null || this.caDir != null) {
            throw new IllegalArgumentException("Certificate authority is already specified via path(s)");
        }
        this.caRoot = PemUtils.cleanUpPem(caRoot);
        // 1024 certs in the chain is the default supported by s2n:
        PemUtils.sanityCheck(this.caRoot, 1024, "CERTIFICATE");
    }

    /**
     * Helper which creates a default set of TLS options for the current platform
     * @return A default configured set of options for a TLS client connection
     */
    public static TlsContextOptions createDefaultClient() {
        TlsContextOptions options = new TlsContextOptions();
        options.verifyPeer = true;
        return options;
    }

    /**
     * Helper which creates a default set of TLS options for the current platform
     *
     * @return A default configured set of options for a TLS server connection
     */
    public static TlsContextOptions createDefaultServer() {
        TlsContextOptions options = new TlsContextOptions();
        options.verifyPeer = false;
        return options;
    }

    /**
     * Helper which creates mutual TLS (mTLS) options using a certificate and private key
     * @param certificatePath Path to a PEM format certificate
     * @param privateKeyPath Path to a PEM format private key
     * @return A set of options for setting up an mTLS connection
     */
    public static TlsContextOptions createWithMtlsFromPath(String certificatePath, String privateKeyPath) {
        TlsContextOptions options = new TlsContextOptions();
        options.initMtlsFromPath(certificatePath, privateKeyPath);
        options.verifyPeer = true;
        return options;
    }

    /**
     * Helper which creates mutual TLS (mTLS) options using a certificate and private key
     *
     * @param certificate String containing a PEM format certificate
     * @param privateKey  String containing a PEM format private key
     * @return A set of options for setting up an mTLS connection
     * @throws IllegalArgumentException If either PEM fails to parse
     */
    public static TlsContextOptions createWithMtls(String certificate, String privateKey)
            throws IllegalArgumentException {
        TlsContextOptions options = new TlsContextOptions();
        options.initMtls(certificate, privateKey);
        options.verifyPeer = true;
        return options;
    }

    /**
     * Apple platforms only - Helper which creates mutual TLS (mTLS) options using PKCS12
     * @param pkcs12Path The path to a PKCS12 file @see #setPkcs12Path(String)
     * @param pkcs12Password The PKCS12 password @see #setPkcs12Password(String)
     * @return A set of options for creating a PKCS12 mTLS connection
     */
    public static TlsContextOptions createWithMtlsPkcs12(String pkcs12Path, String pkcs12Password) {
        TlsContextOptions options = new TlsContextOptions();
        options.initMtlsPkcs12(pkcs12Path, pkcs12Password);
        options.verifyPeer = true;
        return options;
    }

    /**
     * Unix platforms only - Helper which creates mutual TLS (mTLS) options using a PKCS#11 library for private key operations.
     * @param pkcs11Options PKCS#11 options
     * @return A set of options for creating a PKCS#11 mTLS connection
     */
    public static TlsContextOptions createWithMtlsPkcs11(TlsContextPkcs11Options pkcs11Options) {
        TlsContextOptions options = new TlsContextOptions();
        options.withMtlsPkcs11(pkcs11Options);
        options.verifyPeer = true;
        return options;
    }

    /**
     * Unix platforms only - Helper which creates mutual TLS (mTLS) options using the applied custom key operations. This
     * allows you to perform custom private key operations such as signing and decrypting. This is necessary if you
     * require an external library to handle private key operations.
     *
     * @param custom The options for the custom private key operations
     * @return A set of options for creating a custom key operation mTLS connection
     */
    public static TlsContextOptions createWithMtlsCustomKeyOperations(TlsContextCustomKeyOperationOptions custom) {
        TlsContextOptions options = new TlsContextOptions();
        options.withMtlsCustomKeyOperations(custom);
        options.verifyPeer = true;
        return options;
    }

     /**
     * Windows platforms only - Helper which creates mutual TLS (mTLS) options using a
     * certificate in a Windows certificate store.
     *
     * @param certificatePath Path to certificate in a Windows certificate store.
     *                        The path must use backslashes and end with the
     *                        certificate's thumbprint. Example:
     *                        {@code CurrentUser\MY\A11F8A9B5DF5B98BA3508FBCA575D09570E0D2C6}
     * @return A set of options for setting up an mTLS connection
     */
    public static TlsContextOptions createWithMtlsWindowsCertStorePath(String certificatePath) {
        TlsContextOptions options = new TlsContextOptions();
        options.withMtlsWindowsCertStorePath(certificatePath);
        options.verifyPeer = true;
        return options;
    }

    /*******************************************************************************
     * .with() methods
     ******************************************************************************/

    /**
     * Sets the ciphers that the TlsContext will be able to use
     * @param cipherPref The preference set of ciphers to use
     * @return this
     */
    public TlsContextOptions withCipherPreference(TlsCipherPreference cipherPref) {
        setCipherPreference(cipherPref);
        return this;
    }

    /**
     * Sets the minimum TLS version that the TlsContext will allow. Defaults to
     * OS defaults.
     * @param version Minimum acceptable TLS version
     * @return this
     */
    public TlsContextOptions withMinimumTlsVersion(TlsVersions version) {
        minTlsVersion = version;
        return this;
    }

    /**
     * Sets the ALPN protocols list for any connections using this TlsContext
     * @param alpnList Semi-colon delimited list of supported ALPN protocols
     * @return this
     */
    public TlsContextOptions withAlpnList(String alpnList) {
        String[] parts = alpnList.split(";");
        for (String part : parts) {
            this.alpnList.add(part);
        }
        return this;
    }

    /**
     * Enables mutual TLS (mTLS) on this TlsContext
     * @param certificate mTLS certificate, in PEM format
     * @param privateKey mTLS private key, in PEM format
     * @return this
     */
    public TlsContextOptions withMtls(String certificate, String privateKey) {
        this.initMtls(certificate, privateKey);
        return this;
    }

    /**
     * Enables mutual TLS (mTLS) on this TlsContext
     * @param certificatePath path to mTLS certificate, in PEM format
     * @param privateKeyPath path to mTLS private key, in PEM format
     * @return this
     */
    public TlsContextOptions withMtlsFromPath(String certificatePath, String privateKeyPath) {
        this.initMtlsFromPath(certificatePath, privateKeyPath);
        return this;
    }

    /**
     * Specifies the certificate authority to use. By default, the OS CA repository will be used.
     * @param caRoot Certificate Authority, in PEM format
     * @return this
     */
    public TlsContextOptions withCertificateAuthority(String caRoot) {
        this.overrideDefaultTrustStore(caRoot);
        return this;
    }

    /**
     * Specifies the certificate authority to use.
     * @param caDirPath Path to certificate directory, e.g. /etc/ssl/certs
     * @param caFilePath Path to ceritificate authority, in PEM format
     * @return this
     */
    public TlsContextOptions withCertificateAuthorityFromPath(String caDirPath, String caFilePath) {
        this.overrideDefaultTrustStoreFromPath(caDirPath, caFilePath);
        return this;
    }

    /**
     * Apple platforms only, specifies mutual TLS (mTLS) using PKCS#12
     * @param pkcs12Path Path to PKCS#12 certificate, in PEM format
     * @param pkcs12Password PKCS#12 password
     * @return this
     */
    public TlsContextOptions withMtlsPkcs12(String pkcs12Path, String pkcs12Password) {
        this.initMtlsPkcs12(pkcs12Path, pkcs12Password);
        return this;
    }

    /**
     * Unix platforms only, specifies mutual TLS (mTLS) using a PKCS#11 library for private key operations.
     * @param pkcs11Options PKCS#11 options
     * @return this
     */
    public TlsContextOptions withMtlsPkcs11(TlsContextPkcs11Options pkcs11Options) {
        swapReferenceTo(this.pkcs11Options, pkcs11Options);
        this.pkcs11Options = pkcs11Options;
        return this;
    }

    /**
     * Unix platforms only, specifies TLS options for custom private key operations. This
     * allows you to perform custom private key operations such as signing and decrypting.
     *
     * @param customKeyOperations The custom private key operations
     * @return this
     */
    public TlsContextOptions withMtlsCustomKeyOperations(TlsContextCustomKeyOperationOptions customKeyOperations) {
        this.customKeyOperations = customKeyOperations;
        return this;
    }

     /**
     * Windows platforms only, specifies mutual TLS (mTLS) using a certificate in a Windows
     * certificate store.
     *
     * @param certificatePath Path to certificate in a Windows certificate store.
     *                        The path must use backslashes and end with the
     *                        certificate's thumbprint. Example:
     *                        {@code CurrentUser\MY\A11F8A9B5DF5B98BA3508FBCA575D09570E0D2C6}
     * @return this
     */
    public TlsContextOptions withMtlsWindowsCertStorePath(String certificatePath) {
        this.windowsCertStorePath = certificatePath;
        return this;
    }

    /**
     * Sets whether or not TLS will validate the certificate from the peer. On clients,
     * this is enabled by default. On servers, this is disabled by default.
     * @param verify true to verify peers, false to ignore certs
     * @return this
     */
    public TlsContextOptions withVerifyPeer(boolean verify) {
        this.verifyPeer = verify;
        return this;
    }

    /**
     * Enables TLS peer verification of certificates
     * @see TlsContextOptions#withVerifyPeer(boolean)
     * @return this
     */
    public TlsContextOptions withVerifyPeer() {
        return this.withVerifyPeer(true);
    }

    /*******************************************************************************
     * native methods
     ******************************************************************************/
    private static native long tlsContextOptionsNew(
                int minTlsVersion,
                int cipherPref,
                String alpn,
                String certificate,
                String privateKey,
                String certificatePath,
                String privateKeyPath,
                String caRoot,
                String caFile,
                String caDir,
                boolean verifyPeer,
                String pkcs12Path,
                String pkcs12Password,
                TlsContextPkcs11Options pkcs11Options,
                TlsContextCustomKeyOperationOptions customKeyOperation,
                String windowsCertStorePath
            );

    private static native void tlsContextOptionsDestroy(long elg);

    private static native boolean tlsContextOptionsIsAlpnAvailable();

    private static native boolean tlsContextOptionsIsCipherPreferenceSupported(int cipherPref);

};
