package org.acme;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.ChildList;
import com.google.api.services.drive.model.File;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.google.drive.GoogleDriveComponent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

@Path("gdrive")
public class Downloader {

    private final Logger log = LoggerFactory.getLogger(Downloader.class);

    @ConfigProperty(name = "download.folder", defaultValue = "/tmp")
    String downloadFolder;

    @Inject
    ProducerTemplate producerTemplate;

    private static Drive getClient(CamelContext context) {
        GoogleDriveComponent component = context.getComponent("google-drive", GoogleDriveComponent.class);
        return component.getClient(component.getConfiguration());
    }

    @Path("exportFile")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response exportFile(@QueryParam("fileId") String fileId, @QueryParam("mimeType") @DefaultValue("application/vnd.openxmlformats-officedocument.wordprocessingml.document") String mimeType) {
        log.info(">>> exportFile: " + fileId);
        try {
            File response = producerTemplate.requestBody("google-drive://drive-files/get?inBody=fileId", fileId, File.class);
            String ext = null;
            switch (mimeType) {
                case ("application/pdf"):
                    ext = ".pdf";
                    break;
                case ("application/vnd.openxmlformats-officedocument.wordprocessingml.document"):
                default:
                    ext = ".docx";
            }
            // We don't use export method of camel component, rather shortcut cause we know the feed download url's
            if (response != null) {
                String fileName = downloadFolder.concat("/" + response.getTitle().strip().concat(ext));
                try {
                    OutputStream outputStream = new FileOutputStream(fileName);
                    HttpResponse resp = getClient(producerTemplate.getCamelContext()).getRequestFactory()
                            .buildGetRequest(new GenericUrl("https://docs.google.com/feeds/download/documents/export/Export?id=" + fileId + "&exportFormat=" + ext.substring(1))).execute();
                    resp.download(outputStream);
                    outputStream.flush();
                    outputStream.close();
                } catch (IOException e) {
                    return Response.status(Response.Status.NOT_FOUND).build();
                }
                return Response.ok(fileName).build();
            }
            return Response.status(Response.Status.NOT_FOUND).build();

        } catch (CamelExecutionException e) {
            Exception exchangeException = e.getExchange().getException();
            if (exchangeException != null && exchangeException.getCause() instanceof GoogleJsonResponseException) {
                GoogleJsonResponseException originalException = (GoogleJsonResponseException) exchangeException.getCause();
                return Response.status(originalException.getStatusCode()).build();
            }
            throw e;
        }
    }

    @Path("folderList")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response readFolder(@QueryParam("folderId") String folderId) {
        try {
            ChildList response = producerTemplate.requestBody("google-drive://drive-children/list?inBody=folderId", folderId, ChildList.class);
            if (response != null) {
                return Response.ok(response.getItems().toString()).build();
            } else {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
        } catch (CamelExecutionException e) {
            Exception exchangeException = e.getExchange().getException();
            if (exchangeException != null && exchangeException.getCause() instanceof GoogleJsonResponseException) {
                GoogleJsonResponseException originalException = (GoogleJsonResponseException) exchangeException.getCause();
                return Response.status(originalException.getStatusCode()).build();
            }
            throw e;
        }
    }

    @Path("exportFolder")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response exportFolder(@QueryParam("folderId") String folderId) {
        log.info(">>> exportFolder: " + folderId);
        try {
            ChildList response = producerTemplate.requestBody("google-drive://drive-children/list?inBody=folderId", folderId, ChildList.class);
            if (response != null) {
                response.getItems().forEach(
                        child -> exportFile(child.getId(), "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                );
                return Response.ok("Exported " + response.getItems().size() + " documents OK").build();
            } else {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
        } catch (CamelExecutionException e) {
            Exception exchangeException = e.getExchange().getException();
            if (exchangeException != null && exchangeException.getCause() instanceof GoogleJsonResponseException) {
                GoogleJsonResponseException originalException = (GoogleJsonResponseException) exchangeException.getCause();
                return Response.status(originalException.getStatusCode()).build();
            }
            throw e;
        }
    }
}
