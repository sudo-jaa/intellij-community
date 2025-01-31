// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

/* -*-mode:java; c-basic-offset:2; -*- */


package com.intellij.terminal;

import com.google.common.collect.Lists;
import com.intellij.ide.GeneralSettings;
import com.intellij.ide.IdeEventQueue;
import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.TransactionGuard;
import com.intellij.openapi.editor.impl.ComplementaryFontsRegistry;
import com.intellij.openapi.editor.impl.FontInfo;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.util.JBHiDPIScaledImage;
import com.intellij.util.ui.ImageUtil;
import com.intellij.util.ui.UIUtil;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.TerminalPanel;
import org.apache.log4j.Logger;
import org.intellij.lang.annotations.JdkConstants;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.util.List;

public class JBTerminalPanel extends TerminalPanel implements FocusListener, TerminalSettingsListener, Disposable {
  private static final Logger LOG = Logger.getLogger(JBTerminalPanel.class);
  private static final String[] ACTIONS_TO_SKIP = new String[]{
    "ActivateTerminalToolWindow",
    "ActivateMessagesToolWindow",
    "ActivateProjectToolWindow",
    "ActivateFavoritesToolWindow",
    "ActivateFindToolWindow",
    "ActivateRunToolWindow",
    "ActivateDebugToolWindow",
    "ActivateTODOToolWindow",
    "ActivateStructureToolWindow",
    "ActivateHierarchyToolWindow",
    "ActivateServicesToolWindow",
    "ActivateVersionControlToolWindow",
    "HideAllWindows",

    "ShowBookmarks",
    "GotoBookmark0",
    "GotoBookmark1",
    "GotoBookmark2",
    "GotoBookmark3",
    "GotoBookmark4",
    "GotoBookmark5",
    "GotoBookmark6",
    "GotoBookmark7",
    "GotoBookmark8",
    "GotoBookmark9",

    "GotoAction",
    "GotoFile",
    "GotoClass",
    "GotoSymbol",

    "ShowSettings",
    "RecentFiles",
    "Switcher",

    "ResizeToolWindowLeft",
    "ResizeToolWindowRight",
    "ResizeToolWindowUp",
    "ResizeToolWindowDown",
    "MaximizeToolWindow"
  };

  private final TerminalEventDispatcher myEventDispatcher = new TerminalEventDispatcher();
  private final JBTerminalSystemSettingsProviderBase mySettingsProvider;
  private final TerminalEscapeKeyListener myEscapeKeyListener;

  private List<AnAction> myActionsToSkip;

  public JBTerminalPanel(@NotNull JBTerminalSystemSettingsProviderBase settingsProvider,
                         @NotNull TerminalTextBuffer backBuffer,
                         @NotNull StyleState styleState) {
    super(settingsProvider, backBuffer, styleState);

    mySettingsProvider = settingsProvider;

    registerKeymapActions(this);

    addFocusListener(this);

    mySettingsProvider.addListener(this);
    myEscapeKeyListener = new TerminalEscapeKeyListener(this);
  }

  private static void registerKeymapActions(final TerminalPanel terminalPanel) {

    ActionManager actionManager = ActionManager.getInstance();
    for (String actionId : ACTIONS_TO_SKIP) {
      final AnAction action = actionManager.getAction(actionId);
      if (action != null) {
        AnAction a = DumbAwareAction.create(e -> {
          if (e.getInputEvent() instanceof KeyEvent) {
            AnActionEvent event =
              new AnActionEvent(e.getInputEvent(), e.getDataContext(), e.getPlace(), new Presentation(), e.getActionManager(),
                                e.getModifiers());
            action.update(event);
            if (event.getPresentation().isEnabled()) {
              action.actionPerformed(event);
            }
            else {
              terminalPanel.handleKeyEvent((KeyEvent)event.getInputEvent());
            }

            event.getInputEvent().consume();
          }
        });
        for (Shortcut sc : action.getShortcutSet().getShortcuts()) {
          if (sc.isKeyboard() && sc instanceof KeyboardShortcut) {
            KeyboardShortcut ksc = (KeyboardShortcut)sc;
            a.registerCustomShortcutSet(ksc.getFirstKeyStroke().getKeyCode(), ksc.getFirstKeyStroke().getModifiers(), terminalPanel);
          }
        }
      }
    }
  }

  private boolean skipKeyEvent(KeyEvent e) {
    if (myActionsToSkip == null) {
      return false;
    }
    int kc = e.getKeyCode();
    return kc == KeyEvent.VK_ESCAPE || skipAction(e, myActionsToSkip);
  }

  private static boolean skipAction(KeyEvent e, List<? extends AnAction> actionsToSkip) {
    if (actionsToSkip != null) {
      final KeyboardShortcut eventShortcut = new KeyboardShortcut(KeyStroke.getKeyStrokeForEvent(e), null);
      for (AnAction action : actionsToSkip) {
        for (Shortcut sc : action.getShortcutSet().getShortcuts()) {
          if (sc.isKeyboard() && sc.startsWith(eventShortcut)) {
            if (!Registry.is("terminal.Ctrl-E.opens.RecentFiles.popup", false) &&
                IdeActions.ACTION_RECENT_FILES.equals(ActionManager.getInstance().getId(action))) {
              if (e.getModifiersEx() == InputEvent.CTRL_DOWN_MASK && e.getKeyCode() == KeyEvent.VK_E) {
                return false;
              }
            }
            return true;
          }
        }
      }
    }
    return false;
  }

  @Override
  public void handleKeyEvent(@NotNull KeyEvent e) {
    if (SystemInfo.isMac && SystemInfo.isJavaVersionAtLeast(11, 0, 0)) {
      // Workaround for https://youtrack.jetbrains.com/issue/JBR-1098
      // sun.lwawt.macosx.CPlatformResponder.mapNsCharsToCompatibleWithJava converts 0x0003 (NSEnterCharacter) to 0x000a
      if (e.getKeyChar() == KeyEvent.VK_ENTER && e.getKeyCode() == KeyEvent.VK_C && e.getModifiersEx() == InputEvent.CTRL_DOWN_MASK) {
        LOG.debug("Fixing Ctrl+C");
        e.setKeyChar((char)3);
      }
    }
    myEscapeKeyListener.handleKeyEvent(e);
    super.handleKeyEvent(e);
  }

  @Override
  protected void setupAntialiasing(Graphics graphics) {
    UIUtil.setupComposite((Graphics2D)graphics);
    UISettings.setupAntialiasing(graphics);
  }

  @Override
  protected void setCopyContents(StringSelection selection) {
    CopyPasteManager.getInstance().setContents(selection);
  }

  @Override
  protected void drawImage(Graphics2D gfx, BufferedImage image, int x, int y, ImageObserver observer) {
    UIUtil.drawImage(gfx, image, x, y, observer);
  }

  @Override
  protected void drawImage(Graphics2D g, BufferedImage image, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2) {
    drawImage(g, image, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, null);
  }

  public static void drawImage(Graphics g,
                               Image image,
                               int dx1,
                               int dy1,
                               int dx2,
                               int dy2,
                               int sx1,
                               int sy1,
                               int sx2,
                               int sy2,
                               ImageObserver observer) {
    if (image instanceof JBHiDPIScaledImage) {
      final Graphics2D newG = (Graphics2D)g.create(0, 0, image.getWidth(observer), image.getHeight(observer));
      newG.scale(0.5, 0.5);
      Image img = ((JBHiDPIScaledImage)image).getDelegate();
      if (img == null) {
        img = image;
      }
      newG.drawImage(img, 2 * dx1, 2 * dy1, 2 * dx2, 2 * dy2, sx1 * 2, sy1 * 2, sx2 * 2, sy2 * 2, observer);
      newG.scale(1, 1);
      newG.dispose();
    }
    else {
      g.drawImage(image, dx1, dy1, dx2, dy2, sx1, sy1, sx2, sy2, observer);
    }
  }

  @Override
  protected boolean isRetina() {
    return UIUtil.isRetina();
  }

  @Override
  protected BufferedImage createBufferedImage(int width, int height) {
    return ImageUtil.createImage(width, height, BufferedImage.TYPE_INT_ARGB);
  }


  @Override
  public void focusGained(FocusEvent event) {
    installKeyDispatcher();

    if (GeneralSettings.getInstance().isSaveOnFrameDeactivation()) {
      TransactionGuard.submitTransaction(this, () -> FileDocumentManager.getInstance().saveAllDocuments());
    }
  }

  private void installKeyDispatcher() {
    if (mySettingsProvider.overrideIdeShortcuts()) {
      myActionsToSkip = setupActionsToSkip();
      myEventDispatcher.register();
    }
    else {
      myActionsToSkip = null;
    }
  }

  @NotNull
  private static List<AnAction> setupActionsToSkip() {
    List<AnAction> res = Lists.newArrayList();
    ActionManager actionManager = ActionManager.getInstance();
    for (String actionId : ACTIONS_TO_SKIP) {
      AnAction action = actionManager.getAction(actionId);
      if (action != null) {
        res.add(action);
      }
    }
    return res;
  }

  @Override
  public void focusLost(FocusEvent event) {
    if (myActionsToSkip != null) {
      myActionsToSkip = null;
      myEventDispatcher.unregister();
    }

    refreshAfterExecution();
  }

  @Override
  protected Font getFontToDisplay(char c, TextStyle style) {
    FontInfo fontInfo = fontForChar(c, style.hasOption(TextStyle.Option.BOLD) ? Font.BOLD : Font.PLAIN);
    return fontInfo.getFont();
  }

  public FontInfo fontForChar(final char c, @JdkConstants.FontStyle int style) {
    return ComplementaryFontsRegistry.getFontAbleToDisplay(c, style, mySettingsProvider.getColorScheme().getConsoleFontPreferences(), null);
  }

  @Override
  public void fontChanged() {
    reinitFontAndResize();
  }

  @Override
  public void dispose() {
    super.dispose();
    mySettingsProvider.removeListener(this);
  }

  public static void refreshAfterExecution() {
    if (GeneralSettings.getInstance().isSyncOnFrameActivation()) {
      //we need to refresh local file system after a command has been executed in the terminal
      LocalFileSystem.getInstance().refresh(true);
    }
  }

  /**
   * Adds "Override IDE shortcuts" terminal feature allowing terminal to process all the key events.
   * Without own IdeEventQueue.EventDispatcher, terminal won't receive key events corresponding to IDE action shortcuts.
   */
  private class TerminalEventDispatcher implements IdeEventQueue.EventDispatcher {
    @Override
    public boolean dispatch(@NotNull AWTEvent e) {
      if (e instanceof KeyEvent && !skipKeyEvent((KeyEvent)e)) {
        IdeEventQueue.getInstance().flushDelayedKeyEvents();
        // Workaround for https://youtrack.jetbrains.com/issue/IDEA-214782, revert once it's fixed.
        if (SystemInfo.isJavaVersionAtLeast(8, 0, 212)) {
          // JBTerminalPanel is focused, because TerminalEventDispatcher added in focusGained and removed in focusLost
          processKeyEvent((KeyEvent)e);
        }
        dispatchEvent(e);
        return true;
      }
      return false;
    }

    void register() {
      IdeEventQueue.getInstance().addDispatcher(this, JBTerminalPanel.this);
    }

    void unregister() {
      IdeEventQueue.getInstance().removeDispatcher(this);
    }
  }
}
