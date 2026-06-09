package sair.sys.acticity;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

abstract class Acti {

    protected String path;
    protected boolean exists;
    protected URL url;

    protected Acti(String path) throws MalformedURLException {
        this.path = path;
        File file = new File(this.path);
        this.exists = file.exists();
        this.url = file.toURI().toURL();
    }

    public String getPath() {
        return path;
    }

    public URL getURL() {
        return this.url;
    }

    public boolean exists() {
        return exists;
    }

    protected abstract void unLoadJar() throws Exception;
}
