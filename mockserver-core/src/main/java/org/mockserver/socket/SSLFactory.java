package org.mockserver.socket;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.io.IOUtils;
import org.mockserver.configuration.ConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.*;
import java.net.Socket;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

/**
 * @author jamesdbloom
 */
public class SSLFactory {

    public static final String KEY_STORE_CERT_ALIAS = "certAlias";
    public static final String KEY_STORE_CA_ALIAS = "caAlias";
    public static final String KEY_STORE_PASSWORD = "changeit";
    public static final String KEY_STORE_FILENAME = "keystore.jks";
    public static final String PKCS12_FILENAME = "keystore.pkcs12";
    public static final String CERTIFICATE_DOMAIN = "localhost";
    private static final SSLFactory sslFactory = new SSLFactory();
    private static final Logger logger = LoggerFactory.getLogger(SSLFactory.class);
    private static final TrustManager DUMMY_TRUST_MANAGER = new X509TrustManager() {
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            logger.trace("Approving client certificate for: " + chain[0].getSubjectDN());
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            logger.trace("Approving server certificate for: " + chain[0].getSubjectDN());
        }
    };
    private KeyStore keystore;

    @VisibleForTesting
    SSLFactory() {

    }

    public static SSLFactory getInstance() {
        return sslFactory;
    }

    public static SSLEngine createClientSSLEngine() {
        SSLEngine engine = SSLFactory.getInstance().sslContext().createSSLEngine();
        engine.setUseClientMode(true);
        return engine;
    }

    public static SSLEngine createServerSSLEngine() {
        SSLEngine engine = SSLFactory.getInstance().sslContext().createSSLEngine();
        engine.setUseClientMode(false);
        return engine;
    }

    public SSLContext sslContext() {
        try {
            // key manager
            KeyManagerFactory keyManagerFactory = getKeyManagerFactoryInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(buildKeyStore(), ConfigurationProperties.javaKeyStorePassword().toCharArray());

            // ssl context
            SSLContext sslContext = getSSLContextInstance("TLS");
            sslContext.init(keyManagerFactory.getKeyManagers(), new TrustManager[]{DUMMY_TRUST_MANAGER}, null);
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize the SSLContext", e);
        }
    }

    public SSLSocket wrapSocket(Socket socket) throws Exception {
        // ssl socket factory
        SSLSocketFactory sslSocketFactory = sslContext().getSocketFactory();

        // ssl socket
        SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
        sslSocket.setUseClientMode(true);
        sslSocket.startHandshake();
        return sslSocket;
    }

    public KeyStore buildKeyStore() {
        if (keystore == null) {
            File keyStoreFile = new File(ConfigurationProperties.javaKeyStoreFilePath());
            if (keyStoreFile.exists()) {
                loadKeyStore(keyStoreFile);
            } else {
                dynamicallyCreateKeyStore();
            }
        }
        return keystore;
    }

    @VisibleForTesting
    SSLContext getSSLContextInstance(String protocol) throws NoSuchAlgorithmException {
        return SSLContext.getInstance(protocol);
    }

    @VisibleForTesting
    KeyManagerFactory getKeyManagerFactoryInstance(String algorithm) throws NoSuchAlgorithmException {
        return KeyManagerFactory.getInstance(algorithm);
    }

    private void dynamicallyCreateKeyStore() {
        try {
            keystore = new KeyStoreFactory().generateCertificate(
                    KEY_STORE_CERT_ALIAS,
                    KEY_STORE_CA_ALIAS,
                    ConfigurationProperties.javaKeyStorePassword().toCharArray(),
                    ConfigurationProperties.sslCertificateDomainName(),
                    ConfigurationProperties.sslSubjectAlternativeNameDomains(),
                    ConfigurationProperties.sslSubjectAlternativeNameIps()
            );
        } catch (Exception e) {
            throw new RuntimeException("Exception while building KeyStore dynamically", e);
        }
    }

    private void loadKeyStore(File keyStoreFile) {
        try {
            FileInputStream fileInputStream = null;
            try {
                fileInputStream = new FileInputStream(ConfigurationProperties.javaKeyStoreFilePath());
                logger.trace("Loading key store from file [" + keyStoreFile + "]");
                keystore = KeyStore.getInstance(KeyStore.getDefaultType());
                keystore.load(fileInputStream, ConfigurationProperties.javaKeyStorePassword().toCharArray());
            } finally {
                if (fileInputStream != null) {
                    fileInputStream.close();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Exception while loading KeyStore from " + keyStoreFile.getAbsolutePath(), e);
        }
    }

}
