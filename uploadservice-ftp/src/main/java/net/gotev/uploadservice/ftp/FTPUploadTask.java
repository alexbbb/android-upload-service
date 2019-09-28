package net.gotev.uploadservice.ftp;

import net.gotev.uploadservice.UploadServiceConfig;
import net.gotev.uploadservice.UploadTask;
import net.gotev.uploadservice.data.UploadFile;
import net.gotev.uploadservice.logger.UploadServiceLogger;
import net.gotev.uploadservice.network.HttpStack;
import net.gotev.uploadservice.network.ServerResponse;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;
import org.apache.commons.net.io.CopyStreamEvent;
import org.apache.commons.net.io.CopyStreamListener;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * Implements the FTP upload logic.
 * @author Aleksandar Gotev
 */
public class FTPUploadTask extends UploadTask implements CopyStreamListener {

    private static final String LOG_TAG = FTPUploadTask.class.getSimpleName();

    // properties associated to each file
    protected static final String PARAM_REMOTE_PATH = "ftpRemotePath";
    protected static final String PARAM_PERMISSIONS = "ftpPermissions";

    private FTPClient ftpClient = null;

    private FTPUploadTaskParameters getFTPParams() {
        return (FTPUploadTaskParameters) getParams().getAdditionalParameters();
    }

    @Override
    protected void upload(HttpStack httpStack) throws Exception {

        FTPUploadTaskParameters ftpParams = getFTPParams();

        try {
            if (ftpParams.getUseSSL()) {
                final String secureProtocol;

                if (ftpParams.getSecureSocketProtocol().isEmpty())
                    secureProtocol = FTPUploadTaskParameters.DEFAULT_SECURE_SOCKET_PROTOCOL;
                else
                    secureProtocol = ftpParams.getSecureSocketProtocol();

                FTPSClient ftpsClient = new FTPSClient(secureProtocol, ftpParams.isImplicitSecurity());

                // https://tools.ietf.org/html/rfc4217#page-17
                ftpsClient.execPBSZ(0);
                ftpsClient.execPROT("P");

                ftpClient = ftpsClient;

                UploadServiceLogger.debug(LOG_TAG, () -> "Created FTP over SSL (FTPS) client with "
                        + secureProtocol + " protocol and "
                        + (ftpParams.isImplicitSecurity() ? "implicit security" : "explicit security"));

            } else {
                ftpClient = new FTPClient();
            }

            ftpClient.setBufferSize(UploadServiceConfig.getBufferSizeBytes());
            ftpClient.setCopyStreamListener(this);
            ftpClient.setDefaultTimeout(ftpParams.getConnectTimeout());
            ftpClient.setConnectTimeout(ftpParams.getConnectTimeout());
            ftpClient.setAutodetectUTF8(true);

            UploadServiceLogger.debug(LOG_TAG, () -> "Connect timeout set to " + ftpParams.getConnectTimeout() + "ms");

            UploadServiceLogger.debug(LOG_TAG, () -> "Connecting to " + params.getServerUrl()
                                  + ":" + ftpParams.getPort() + " as " + ftpParams.getUsername());
            ftpClient.connect(params.getServerUrl(), ftpParams.getPort());

            if (!FTPReply.isPositiveCompletion(ftpClient.getReplyCode())) {
                throw new Exception("Can't connect to " + params.getServerUrl()
                                    + ":" + ftpParams.getPort()
                                    + ". The server response is: " + ftpClient.getReplyString());
            }

            String username = ftpParams.getUsername();
            String password = ftpParams.getPassword();

            if (username != null && password != null) {
                if (!ftpClient.login(ftpParams.getUsername(), ftpParams.getPassword())) {
                    throw new Exception("Error while performing login on " + params.getServerUrl()
                            + ":" + ftpParams.getPort()
                            + " with username: " + ftpParams.getUsername()
                            + ". Check your credentials and try again.");
                }
            } else {
                UploadServiceLogger.info(LOG_TAG, () -> "Skipping login as username or password are not provided");
            }

            // to prevent the socket timeout on the control socket during file transfer,
            // set the control keep alive timeout to a half of the socket timeout
            int controlKeepAliveTimeout = ftpParams.getSocketTimeout() / 2 / 1000;

            ftpClient.setSoTimeout(ftpParams.getSocketTimeout());
            ftpClient.setControlKeepAliveTimeout(controlKeepAliveTimeout);
            ftpClient.setControlKeepAliveReplyTimeout(controlKeepAliveTimeout * 1000);

            UploadServiceLogger.debug(LOG_TAG, () -> "Socket timeout set to " + ftpParams.getSocketTimeout()
                         + "ms. Enabled control keep alive every " + controlKeepAliveTimeout + "s");

            ftpClient.enterLocalPassiveMode();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            ftpClient.setFileTransferMode(ftpParams.isCompressedFileTransfer() ?
                                          FTP.COMPRESSED_TRANSFER_MODE : FTP.STREAM_TRANSFER_MODE);

            // this is needed to calculate the total bytes and the uploaded bytes, because if the
            // request fails, the upload method will be called again
            // (until max retries is reached) to retry the upload, so it's necessary to
            // know at which status we left, to be able to properly notify firther progress.
            calculateUploadedAndTotalBytes();

            String baseWorkingDir = ftpClient.printWorkingDirectory();
            UploadServiceLogger.debug(LOG_TAG, () -> "FTP default working directory is: " + baseWorkingDir);

            Iterator<UploadFile> iterator = new ArrayList<>(params.getFiles()).iterator();
            while (iterator.hasNext()) {
                UploadFile file = iterator.next();

                if (!getShouldContinue())
                    break;

                uploadFile(baseWorkingDir, file);
                file.setSuccessfullyUploaded(true);
                iterator.remove();
            }

            // Broadcast completion only if the user has not cancelled the operation.
            if (getShouldContinue()) {
                onResponseReceived(ServerResponse.Companion.successfulEmpty());
            }

        } finally {
            if (ftpClient.isConnected()) {
                try {
                    UploadServiceLogger.debug(LOG_TAG, () -> "Logout and disconnect from FTP server: "
                                          + params.getServerUrl() + ":" + ftpParams.getPort());
                    ftpClient.logout();
                    ftpClient.disconnect();
                } catch (Exception exc) {
                    UploadServiceLogger.error(LOG_TAG, exc, () -> "Error while closing FTP connection to: "
                                          + params.getServerUrl() + ":" + ftpParams.getPort());
                }
            }
            ftpClient = null;
        }
    }

    /**
     * Calculates the total bytes of this upload task.
     * This the sum of all the lengths of the successfully uploaded files and also the pending
     * ones.
     */
    private void calculateUploadedAndTotalBytes() {
        resetUploadedBytes();

        long totalUploaded = 0;

        for (UploadFile file : getSuccessfullyUploadedFiles()) {
            totalUploaded += file.getHandler().size(context);
        }

        long totalBytes = totalUploaded;

        for (UploadFile file : params.getFiles()) {
            totalBytes += file.getHandler().size(context);
        }

        setTotalBytes(totalBytes);
        onProgress(totalUploaded);
    }

    private void uploadFile(String baseWorkingDir, UploadFile file) throws IOException {
        UploadServiceLogger.debug(LOG_TAG, () -> "Starting FTP upload of: " + file.getHandler().name(context)
                              + " to: " + file.getProperties().get(PARAM_REMOTE_PATH));

        String remoteDestination = file.getProperties().get(PARAM_REMOTE_PATH);

        if (remoteDestination.startsWith(baseWorkingDir)) {
            remoteDestination = remoteDestination.substring(baseWorkingDir.length());
        }

        makeDirectories(remoteDestination, getFTPParams().getCreatedDirectoriesPermissions());

        InputStream localStream = file.getHandler().stream(context);
        try {
            String remoteFileName = getRemoteFileName(file);
            if (!ftpClient.storeFile(remoteFileName, localStream)) {
                throw new IOException("Error while uploading: " + file.getHandler().name(context)
                                      + " to: " + file.getProperties().get(PARAM_REMOTE_PATH));
            }

            setPermission(remoteFileName, file.getProperties().get(PARAM_PERMISSIONS));

        } finally {
            localStream.close();
        }

        // get back to base working directory
        if (!ftpClient.changeWorkingDirectory(baseWorkingDir)) {
            UploadServiceLogger.info(LOG_TAG, () -> "Can't change working directory to: " + baseWorkingDir);
        }
    }

    private void setPermission(String remoteFileName, String permissions) {
        if (permissions == null || "".equals(permissions))
            return;

        // http://stackoverflow.com/questions/12741938/how-can-i-change-permissions-of-a-file-on-a-ftp-server-using-apache-commons-net
        try {
            if (ftpClient.sendSiteCommand("chmod " + permissions + " " + remoteFileName)) {
                UploadServiceLogger.error(LOG_TAG, () -> "Error while setting permissions for: "
                        + remoteFileName + " to: " + permissions
                        + ". Check if your FTP user can set file permissions!");
            } else {
                UploadServiceLogger.debug(LOG_TAG, () -> "Permissions for: " + remoteFileName + " set to: " + permissions);
            }
        } catch (IOException exc) {
            UploadServiceLogger.error(LOG_TAG, exc, () -> "Error while setting permissions for: "
                    + remoteFileName + " to: " + permissions
                    + ". Check if your FTP user can set file permissions!");
        }
    }

    @Override
    public void bytesTransferred(CopyStreamEvent event) {
    }

    @Override
    public void bytesTransferred(long totalBytesTransferred, int bytesTransferred, long streamSize) {
        onProgress(bytesTransferred);

        if (!getShouldContinue()) {
            try {
                ftpClient.disconnect();
            } catch (Exception exc) {
                UploadServiceLogger.error(LOG_TAG, exc, () -> "Failed to abort current file transfer");
            }
        }
    }

    /**
     * Creates a nested directory structure on a FTP server and enters into it.
     * @param dirPath Path of the directory, i.e /projects/java/ftp/demo
     * @param permissions UNIX permissions to apply to created directories. If null, the FTP
     *                    server defaults will be applied, because no UNIX permissions will be
     *                    explicitly set
     * @throws IOException if any error occurred during client-server communication
     */
    private void makeDirectories(String dirPath, String permissions) throws IOException {
        if (!dirPath.contains("/")) return;

        String[] pathElements = dirPath.split("/");

        if (pathElements.length == 1) return;

        // if the string ends with / it means that the dir path contains only directories,
        // otherwise if it does not contain /, the last element of the path is the file name,
        // so it must be ignored when creating the directory structure
        int lastElement = dirPath.endsWith("/") ? pathElements.length : pathElements.length - 1;

        for (int i = 0; i < lastElement; i++) {
            String singleDir = pathElements[i];
            if (singleDir.isEmpty()) continue;

            if (!ftpClient.changeWorkingDirectory(singleDir)) {
                if (ftpClient.makeDirectory(singleDir)) {
                    UploadServiceLogger.debug(LOG_TAG, () -> "Created remote directory: " + singleDir);
                    if (permissions != null) {
                        setPermission(singleDir, permissions);
                    }
                    ftpClient.changeWorkingDirectory(singleDir);
                } else {
                    throw new IOException("Unable to create remote directory: " + singleDir);
                }
            }
        }
    }

    /**
     * Checks if the remote file path contains also the remote file name. If it's not specified,
     * the name of the local file will be used.
     * @param file file to upload
     * @return remote file name
     */
    private String getRemoteFileName(UploadFile file) {

        // if the remote path ends with /
        // it means that the remote path specifies only the directory structure, so
        // get the remote file name from the local file
        if (file.getProperties().get(PARAM_REMOTE_PATH).endsWith("/")) {
            return file.getHandler().name(context);
        }

        // if the remote path contains /, but it's not the last character
        // it means that I have something like: /path/to/myfilename
        // so the remote file name is the last path element (myfilename in this example)
        if (file.getProperties().get(PARAM_REMOTE_PATH).contains("/")) {
            String[] tmp = file.getProperties().get(PARAM_REMOTE_PATH).split("/");
            return tmp[tmp.length - 1];
        }

        // if the remote path does not contain /, it means that it specifies only
        // the remote file name
        return file.getProperties().get(PARAM_REMOTE_PATH);
    }
}
