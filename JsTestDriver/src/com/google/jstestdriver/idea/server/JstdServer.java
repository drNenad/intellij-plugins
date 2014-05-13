package com.google.jstestdriver.idea.server;

import com.google.common.base.Charsets;
import com.google.gson.stream.JsonWriter;
import com.google.jstestdriver.JsTestDriverServer;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.*;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.PathUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class JstdServer {

  private static final Logger LOG = Logger.getInstance(JstdServer.class);
  private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

  private final JstdServerSettings mySettings;
  private final OSProcessHandler myProcessHandler;
  private final String myName;
  private final MyDisposable myDisposable;
  private final JstdServerOutputProcessor myOutputProcessor;
  private final JstdServerLifeCycleManager myLifeCycleManager;

  public JstdServer(@NotNull JstdServerSettings settings) throws IOException {
    int id = NEXT_ID.getAndIncrement();
    mySettings = settings;
    myProcessHandler = start(settings, id);
    myProcessHandler.startNotify();
    myName = formatName(id, myProcessHandler.getProcess());
    myOutputProcessor = new JstdServerOutputProcessor(myProcessHandler);
    myLifeCycleManager = new JstdServerLifeCycleManager();
    myOutputProcessor.addListener(myLifeCycleManager);
    myDisposable = new MyDisposable();
    myProcessHandler.addProcessListener(new ProcessAdapter() {
      @Override
      public void processTerminated(ProcessEvent event) {
        LOG.info(myName + " terminated with exit code " + event.getExitCode());
        myLifeCycleManager.onTerminated();
        Disposer.dispose(myDisposable);
      }
    });
    Disposer.register(ApplicationManager.getApplication(), myDisposable);
  }

  @NotNull
  public ProcessHandler getProcessHandler() {
    return myProcessHandler;
  }

  public boolean isProcessRunning() {
    return !myProcessHandler.isProcessTerminated();
  }

  @NotNull
  public JstdServerSettings getSettings() {
    return mySettings;
  }

  @NotNull
  public String getServerUrl() {
    return "http://127.0.0.1:" + mySettings.getPort();
  }

  @NotNull
  public Collection<JstdBrowserInfo> getCapturedBrowsers() {
    return myLifeCycleManager.getCapturedBrowsers();
  }

  public boolean isStopped() {
    return myLifeCycleManager.isServerStopped();
  }

  @NotNull
  private static OSProcessHandler start(@NotNull JstdServerSettings settings, int id) throws IOException {
    GeneralCommandLine commandLine = createCommandLine(settings);
    OSProcessHandler processHandler;
    try {
      processHandler = new KillableColoredProcessHandler(commandLine);
    }
    catch (ExecutionException e) {
      throw new IOException("Cannot start " + formatName(id, null) + ".\nCommand: " + commandLine.getCommandLineString(), e);
    }
    LOG.info(formatName(id, processHandler.getProcess()) + " started successfully: " + commandLine.getCommandLineString());
    ProcessTerminatedListener.attach(processHandler);
    processHandler.setShouldDestroyProcessRecursively(true);
    return processHandler;
  }

  private static String formatName(int id, @Nullable Process process) {
    String name = "JstdServer#" + id;
    if (process != null && SystemInfo.isUnix) {
      try {
        int pid = UnixProcessManager.getProcessPid(process);
        name += " (pid " + pid + ")";
      }
      catch (Exception ignored) {
      }
    }
    return name;
  }

  private static GeneralCommandLine createCommandLine(@NotNull JstdServerSettings settings) {
    GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setExePath(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
    Charset charset = Charsets.UTF_8;
    commandLine.setCharset(charset);
    commandLine.addParameter("-Dfile.encoding=" + charset.name());
    //commandLine.addParameter("-Xdebug");
    //commandLine.addParameter("-Xrunjdwp:transport=dt_socket,address=5000,server=y,suspend=y");

    File file = new File(PathUtil.getJarPathForClass(JsTestDriverServer.class));
    commandLine.setWorkDirectory(file.getParentFile());

    commandLine.addParameter("-cp");
    commandLine.addParameter(getClasspath());

    commandLine.addParameter("com.google.jstestdriver.idea.server.JstdServerMain");

    commandLine.addParameter("--port");
    commandLine.addParameter(String.valueOf(settings.getPort()));

    commandLine.addParameter("--runnerMode");
    commandLine.addParameter(settings.getRunnerMode().name());

    commandLine.addParameter("--browserTimeout");
    commandLine.addParameter(String.valueOf(settings.getBrowserTimeoutMillis()));

    return commandLine;
  }

  @NotNull
  private static String getClasspath() {
    Class[] classes = new Class[] {JsTestDriverServer.class, JstdServerMain.class, JsonWriter.class};
    List<String> result = ContainerUtil.newArrayList();
    for (Class clazz : classes) {
      String path = PathUtil.getJarPathForClass(clazz);
      File file = new File(path);
      result.add(file.getAbsolutePath());
    }
    return StringUtil.join(result, File.pathSeparator);
  }

  public void addOutputListener(@NotNull final JstdServerOutputListener listener) {
    UIUtil.invokeLaterIfNeeded(new Runnable() {
      @Override
      public void run() {
        myOutputProcessor.addListener(listener);
      }
    });
  }

  public void addLifeCycleListener(@NotNull final JstdServerLifeCycleListener listener, @NotNull final Disposable disposable) {
    myLifeCycleManager.addListener(listener, disposable);
  }

  public void shutdownAsync() {
    if (!myProcessHandler.isProcessTerminated()) {
      LOG.info(myName + ": shutting down asynchronously...");
      ApplicationManager.getApplication().executeOnPooledThread(new Runnable() {
        @Override
        public void run() {
          if (!myProcessHandler.isProcessTerminated()) {
            ScriptRunnerUtil.terminateProcessHandler(myProcessHandler, 1000, myProcessHandler.getCommandLine());
          }
        }
      });
    }
  }

  @Override
  public String toString() {
    return myName;
  }

  private class MyDisposable implements Disposable {

    private volatile boolean myDisposed = false;

    @Override
    public void dispose() {
      if (myDisposed) {
        return;
      }
      LOG.info("Disposing " + myName);
      shutdownAsync();
      myOutputProcessor.dispose();
    }
  }

}
