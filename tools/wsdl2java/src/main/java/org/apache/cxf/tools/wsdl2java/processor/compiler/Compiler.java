/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.tools.wsdl2java.processor.compiler;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

public class Compiler {
    public boolean internalCompile(String[] args, int sourceFileIndex) {
        Process p = null;
        String cmdArray[] = null;
      
        try {
            if (isLongCommandLines(args) && sourceFileIndex >= 0) {
                PrintWriter out = null;
                File tmpFile = File.createTempFile("cxf-compiler", null);
                tmpFile.deleteOnExit();
                out = new PrintWriter(new FileWriter(tmpFile));
                for (int i = sourceFileIndex; i < args.length; i++) {
                    if (args[i].indexOf(" ") > -1) {
                        args[i] = args[i].replace(File.separatorChar, '/');
                        out.println("\"" + args[i] + "\"");
                    } else {
                        out.println(args[i]);
                    }
                }
                out.flush();
                cmdArray = new String[sourceFileIndex + 1];
                System.arraycopy(args, 0, cmdArray, 0, sourceFileIndex);
                cmdArray[sourceFileIndex] = "@" + tmpFile;
            } else {
                /*//Sun's jdk bug on windows xp and windows 2000. It will be fixed in mustung 
                //get windows
                if (System.getProperty("os.name").toLowerCase().indexOf("windows") > -1) {
                    for (int i = 0; i < args.length; i++) {
                        if (!"/".equals(File.separator) && args[i].indexOf("package-info") == -1) {
                            args[i] = args[i].replace(File.separatorChar, '/');
                        }
                    }
                }
               */
                cmdArray = new String[args.length];
                System.arraycopy(args, 0, cmdArray, 0, args.length);
            }
            
            p = Runtime.getRuntime().exec(cmdArray);

            if (p.getErrorStream() != null) {
                StreamPrinter errorStreamPrinter = new StreamPrinter(p.getErrorStream(), "", System.out);
                errorStreamPrinter.run();
            }

            if (p.getInputStream() != null) {
                StreamPrinter infoStreamPrinter = new StreamPrinter(p.getInputStream(), "[INFO]", System.out);
                infoStreamPrinter.run();
            }

            if (p != null) {
                return p.waitFor() == 0 ? true : false;
            }
        } catch (SecurityException e) {
            System.err.println("[ERROR] SecurityException during exec() of compiler \"" + args[0] + "\".");
        } catch (InterruptedException e) {
            // ignore

        } catch (IOException e) {
            System.err.print("[ERROR] IOException during exec() of compiler \"" + args[0] + "\"");
            System.err.println(". Check your path environment variable.");
        }

        return false;
    }

    private boolean isLongCommandLines(String args[]) {
        StringBuffer strBuffer = new StringBuffer();
        for (int i = 0; i < args.length; i++) {
            strBuffer.append(args[i]);
        }
        return strBuffer.toString().length() > 4096 ? true : false;
    }
}
