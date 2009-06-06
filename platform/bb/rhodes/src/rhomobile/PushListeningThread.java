package rhomobile;

import java.io.*;
import java.util.Vector;

import javax.microedition.io.*;

import com.rho.RhoEmptyLogger;
import com.rho.RhoLogger;
import com.rho.sync.SyncEngine;
import com.rho.RhoConf;

import net.rim.device.api.ui.component.*;
import net.rim.device.api.system.*;
import net.rim.device.api.util.*;
import net.rim.device.api.io.http.*;

public class PushListeningThread extends Thread {
	private static final RhoLogger LOG = RhoLogger.RHO_STRIP_LOG ? new RhoEmptyLogger() : 
		new RhoLogger("PushListeningThread");
	
	// Constants ----------------------------------------------------------------
    private static final String URL = "http://:"; // PORT 100.
    private static final int CHUNK_SIZE = 256;
    
    //
    private boolean _stop = false;
    private StreamConnectionNotifier _notify;

    protected synchronized void stop()
    {
        _stop = true;
        try 
        {
            // Close the connection so the thread will return.
            _notify.close(); 
        } 
        catch (IOException e) 
        {
            System.err.println(e.toString());
        } 
        catch (NullPointerException e) 
        {
            // The notify object likely failed to open, due to an IOException.
        }
    }

    public void run()
    {

        StreamConnection stream = null;
        InputStream input = null;
        MDSPushInputStream pushInputStream=null;
        
        while (!_stop)
        {
        try 
            {
                
                // Synchronize here so that we don't end up creating a connection that is never closed.
                synchronized(this)  
                {
                    // Open the connection once (or re-open after an IOException),  so we don't end up 
                    // in a race condition, where a push is lost if it comes in before the connection 
                    // is open again. We open the url with a parameter that indicates that we should 
                    // always use MDS when attempting to connect.
                	int port = RhoConf.getInstance().getInt("push_port");
                	if (port == 0) port = 100;
                    _notify = (StreamConnectionNotifier)Connector.open(URL + port + ";deviceside=false");
                }
                
                while (!_stop)
                {
                    
                    // NOTE: the following will block until data is received.
                	LOG.TRACE("Block push thread until data is recieved");
                    stream = _notify.acceptAndOpen();
                	LOG.TRACE("Recieved push data");
                    
                    try 
                    {
                        input = stream.openInputStream();
                        pushInputStream = new MDSPushInputStream((HttpServerConnection)stream, input);

                        // Extract the data from the input stream.
                     
                        DataBuffer db = new DataBuffer();
                        byte[] data = new byte[CHUNK_SIZE];
                        int chunk = 0;
                        
                        while ( -1 != (chunk = input.read(data)) )
                        {
                            db.write(data, 0, chunk);
                        }
                        
                        processPushMessage(data);
                        
                        // This method is called to accept the push.
                        pushInputStream.accept();

                        input.close();
                        stream.close();

                        data = db.getArray();
                        

                    } 
                    catch (IOException e1) 
                    {
                        // A problem occurred with the input stream , however, the original 
                        // StreamConnectionNotifier is still valid.
                        System.err.println(e1.toString());

                        if ( input != null ) 
                        {
                            try 
                            {
                                input.close();
                            } 
                            catch (IOException e2) 
                            {
                            }
                        }
                        
                        if ( stream != null )
                        {
                            try 
                            {
                                stream.close();
                            } 
                            catch (IOException e2) 
                            {
                            }
                        }
                    }
                }
                
                _notify.close();
                _notify = null;   
                
            } 
            catch (IOException ioe)
            {
            	LOG.TRACE("Exception thrown by _notify.acceptAndOpen() - exiting push thread");
            	
            	// Likely the stream was closed. Catches the exception thrown by 
                // _notify.acceptAndOpen() when this program exits.
            	
            	_stop = true;
                
                if ( _notify != null ) 
                {
                    try 
                    {
                        _notify.close();
                        _notify = null;
                    } 
                    catch ( IOException e ) 
                    {
                    }
                }
            }
        }
    }
    
    private String[] split(String original, String separator) {
		Vector nodes = new Vector();
		// Parse nodes into vector
		int index = original.indexOf(separator);
		while (index >= 0) {
			nodes.addElement(original.substring(0, index));
			original = original.substring(index + separator.length());
			index = original.indexOf(separator);
		}
		// Get the last node
		nodes.addElement(original);

		// Create splitted string array
		String[] result = new String[nodes.size()];
		if (nodes.size() > 0) {
			for (int loop = 0; loop < nodes.size(); loop++) {
				result[loop] = ((String)nodes.elementAt(loop)).trim();
			}
		}

		return result;
	}
   
    private String[] splitOnce(String original, String separator) {
		String[] result;
    	int index = original.indexOf(separator);
    	if (index>=0) {
    		result = new String[2];
    		result[0] = original.substring(0, index).trim();
    		result[1] = original.substring(index + separator.length()).trim();
    	} else {
    		result = new String[1];
    		result[0] = original.trim();
    	}
    	return result;
    }
    
    private void showPopup(String message) {
		String[] choices = { "Ok" };
		Application.getApplication().requestForeground();
		Dialog.ask(message,choices,0);
	}
    
    private void vibrate(String duration) {
    	int dt = 2500;
    	try {
    		dt = Integer.parseInt(duration);
    	} catch (NumberFormatException e) {    		
    	}
    	
    	if (dt > 25500) dt = 25500;
    	
    	if (dt > 0) {
    		Alert.startVibrate(dt);
    	}
    }
    
    private void processPushMessage(final byte[] data)
    {
        Application.getApplication().invokeLater(new Runnable() 
        {
            public void run() 
            {
            	String msg = new String(data);
            	LOG.TRACE("Triger sync on PUSH message [" + msg + " ]\n");

            	String[] op;
            	String[] ops = split(msg,"\n");
            	for (int loop = 0; loop < ops.length; loop++) {
            		if (ops[loop].startsWith("do_sync")) {
                    	SyncEngine.dosync(null);
            		} else if (ops[loop].startsWith("show_popup")) {
            			op = splitOnce(ops[loop],"=");
            			if (op.length>1) {
            				showPopup(op[1]);
            			}
            		} else if (ops[loop].startsWith("vibrate")) {
            			op = splitOnce(ops[loop],"=");
            			if (op.length>1) {
            				vibrate(op[1]);
            			} else {
            				vibrate("2500");
            			}
            		}
            	}
            	
            }
        });
    }

}
