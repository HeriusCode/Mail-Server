package MailServer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

public class MailServerGUI extends JFrame {
    private static final Color BACKGROUND = new Color(244, 247, 251);
    private static final Color SURFACE = Color.WHITE;
    private static final Color NAVY = new Color(15, 23, 42);
    private static final Color PRIMARY = new Color(79, 70, 229);
    private static final Color TEXT = new Color(30, 41, 59);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color BORDER = new Color(226, 232, 240);
    private static final Color SUCCESS = new Color(5, 150, 105);
    private static final Color ERROR = new Color(220, 38, 38);
    private static final String DEFAULT_BIND_HOST = "0.0.0.0";
    private static final int DEFAULT_PORT = 5000;

    private final JTextField txtBindHost = new JTextField(DEFAULT_BIND_HOST, 11);
    private final JTextField txtPort = new JTextField(String.valueOf(DEFAULT_PORT), 5);
    private final JButton btnStart = createButton("Khởi động", PRIMARY, Color.WHITE);
    private final JButton btnStop = createButton("Dừng server", ERROR, Color.WHITE);
    private final JButton btnClearLog = createButton("Xóa nhật ký",
            new Color(241, 245, 249), TEXT);
    private final JLabel lblStatus = new JLabel("● OFFLINE");
    private final JLabel lblClientMetric = new JLabel("0");
    private final JLabel lblRequestMetric = new JLabel("0");
    private final JLabel lblProtocolMetric = new JLabel("UDP");
    private final JTextArea txtLog = new JTextArea();
    private final Set<InetSocketAddress> knownClients = ConcurrentHashMap.newKeySet();
    private final AtomicInteger requestCount = new AtomicInteger();

    private volatile UdpMailServer udpServer;
    private volatile boolean running;

    public MailServerGUI() {
        super("MailFlow UDP Server");
        buildInterface();
    }

    private void buildInterface() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900, 620));
        setSize(1080, 700);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BACKGROUND);
        setLayout(new BorderLayout());
        add(createSidebar(), BorderLayout.WEST);

        JPanel main = new JPanel(new BorderLayout(0, 18));
        main.setOpaque(false);
        main.setBorder(new EmptyBorder(22, 24, 20, 24));
        main.add(createHeader(), BorderLayout.NORTH);
        main.add(createDashboard(), BorderLayout.CENTER);
        add(main, BorderLayout.CENTER);

        btnStop.setEnabled(false);
        btnStart.addActionListener(event -> startServer());
        btnStop.addActionListener(event -> stopServer());
        btnClearLog.addActionListener(event -> txtLog.setText(""));
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                stopServer();
                dispose();
            }
        });
    }

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(215, 0));
        sidebar.setBackground(NAVY);
        sidebar.setBorder(new EmptyBorder(26, 18, 24, 18));
        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        brand.setOpaque(false);
        JLabel logo = new JLabel("S", SwingConstants.CENTER);
        logo.setPreferredSize(new Dimension(40, 40));
        logo.setOpaque(true);
        logo.setBackground(PRIMARY);
        logo.setForeground(Color.WHITE);
        logo.setFont(new Font("Segoe UI", Font.BOLD, 21));
        JLabel name = new JLabel("<html><b><font color='white' size='+1'>MailFlow</font></b>"
                + "<br><font color='#94A3B8'>Server Console</font></html>");
        name.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        brand.add(logo);
        brand.add(name);
        sidebar.add(brand, BorderLayout.NORTH);

        JPanel nav = new JPanel();
        nav.setOpaque(false);
        nav.setLayout(new javax.swing.BoxLayout(nav, javax.swing.BoxLayout.Y_AXIS));
        JLabel navTitle = new JLabel("QUẢN TRỊ MÁY CHỦ");
        navTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        navTitle.setForeground(new Color(100, 116, 139));
        navTitle.setFont(new Font("Segoe UI", Font.BOLD, 10));
        navTitle.setBorder(new EmptyBorder(42, 10, 12, 0));
        nav.add(navTitle);
        nav.add(createNavItem("TỔNG QUAN", true));
        nav.add(createNavItem("UDP ENDPOINT", false));
        nav.add(createNavItem("NHẬT KÝ", false));
        sidebar.add(nav, BorderLayout.CENTER);

        RoundedPanel info = new RoundedPanel(14, new Color(30, 41, 59));
        info.setLayout(new BorderLayout(0, 5));
        info.setBorder(new EmptyBorder(12, 13, 12, 13));
        JLabel title = new JLabel("GIAO THỨC");
        title.setForeground(new Color(165, 180, 252));
        title.setFont(new Font("Segoe UI", Font.BOLD, 10));
        JLabel description = new JLabel("<html><font color='#CBD5E1'>UDP + Request ID<br>Chống xử lý trùng</font></html>");
        description.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        info.add(title, BorderLayout.NORTH);
        info.add(description, BorderLayout.CENTER);
        sidebar.add(info, BorderLayout.SOUTH);
        return sidebar;
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new javax.swing.BoxLayout(titles, javax.swing.BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Tổng quan máy chủ");
        title.setFont(new Font("Segoe UI", Font.BOLD, 27));
        title.setForeground(TEXT);
        JLabel subtitle = new JLabel("Theo dõi lưu lượng và vận hành dịch vụ mail UDP");
        subtitle.setForeground(MUTED);
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        titles.add(title);
        titles.add(subtitle);
        header.add(titles, BorderLayout.WEST);
        lblStatus.setOpaque(true);
        lblStatus.setBackground(new Color(254, 226, 226));
        lblStatus.setForeground(ERROR);
        lblStatus.setFont(new Font("Segoe UI", Font.BOLD, 11));
        lblStatus.setBorder(new EmptyBorder(9, 13, 9, 13));
        header.add(lblStatus, BorderLayout.EAST);
        return header;
    }

    private JPanel createDashboard() {
        JPanel dashboard = new JPanel(new BorderLayout(0, 16));
        dashboard.setOpaque(false);

        JPanel top = new JPanel(new BorderLayout(0, 14));
        top.setOpaque(false);
        top.add(createConfigurationCard(), BorderLayout.NORTH);
        JPanel metrics = new JPanel(new GridLayout(1, 3, 14, 0));
        metrics.setOpaque(false);
        metrics.add(createMetricCard("CLIENT ĐÃ GHI NHẬN", lblClientMetric,
                new Color(59, 130, 246), "Địa chỉ IP:port duy nhất"));
        metrics.add(createMetricCard("YÊU CẦU ĐÃ XỬ LÝ", lblRequestMetric,
                new Color(139, 92, 246), "Không tính gói retry trùng"));
        metrics.add(createMetricCard("GIAO THỨC", lblProtocolMetric,
                SUCCESS, "Datagram • tối đa 60 KB"));
        top.add(metrics, BorderLayout.CENTER);
        dashboard.add(top, BorderLayout.NORTH);
        dashboard.add(createLogCard(), BorderLayout.CENTER);
        return dashboard;
    }

    private JPanel createConfigurationCard() {
        RoundedPanel card = new RoundedPanel(17, SURFACE);
        card.setLayout(new BorderLayout(12, 0));
        card.setBorder(new EmptyBorder(14, 17, 14, 17));
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 0));
        form.setOpaque(false);
        JLabel icon = new JLabel("UDP", SwingConstants.CENTER);
        icon.setPreferredSize(new Dimension(35, 35));
        icon.setOpaque(true);
        icon.setBackground(new Color(238, 242, 255));
        icon.setForeground(PRIMARY);
        icon.setFont(new Font("Segoe UI", Font.BOLD, 9));
        form.add(icon);
        form.add(createSmallLabel("Bind IP"));
        styleField(txtBindHost);
        form.add(txtBindHost);
        form.add(createSmallLabel("Port"));
        styleField(txtPort);
        form.add(txtPort);
        card.add(form, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(btnStop);
        actions.add(btnStart);
        card.add(actions, BorderLayout.EAST);
        return card;
    }

    private JPanel createMetricCard(String title, JLabel value, Color accent, String note) {
        RoundedPanel card = new RoundedPanel(17, SURFACE);
        card.setLayout(new BorderLayout(0, 3));
        card.setBorder(new EmptyBorder(16, 18, 15, 18));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(MUTED);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 10));
        value.setForeground(accent);
        value.setFont(new Font("Segoe UI", Font.BOLD, 28));
        JLabel noteLabel = new JLabel(note);
        noteLabel.setForeground(MUTED);
        noteLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        card.add(titleLabel, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        card.add(noteLabel, BorderLayout.SOUTH);
        return card;
    }

    private JPanel createLogCard() {
        RoundedPanel card = new RoundedPanel(18, SURFACE);
        card.setLayout(new BorderLayout(0, 12));
        card.setBorder(new EmptyBorder(16, 17, 16, 17));
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        JLabel title = new JLabel("Nhật ký hoạt động");
        title.setForeground(TEXT);
        title.setFont(new Font("Segoe UI", Font.BOLD, 17));
        heading.add(title, BorderLayout.WEST);
        heading.add(btnClearLog, BorderLayout.EAST);
        card.add(heading, BorderLayout.NORTH);

        txtLog.setEditable(false);
        txtLog.setLineWrap(true);
        txtLog.setWrapStyleWord(true);
        txtLog.setBackground(new Color(15, 23, 42));
        txtLog.setForeground(new Color(167, 243, 208));
        txtLog.setCaretColor(Color.WHITE);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 13));
        txtLog.setBorder(new EmptyBorder(13, 14, 13, 14));
        JScrollPane scroll = new JScrollPane(txtLog);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(30, 41, 59)));
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    private void startServer() {
        if (running) return;
        String host = txtBindHost.getText().trim();
        if (host.isEmpty()) host = DEFAULT_BIND_HOST;
        final int port;
        try {
            port = Integer.parseInt(txtPort.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            appendLog("Port không hợp lệ. Port phải từ 1 đến 65535.");
            return;
        }
        final String bindHost = host;
        knownClients.clear();
        requestCount.set(0);
        updateStatistics();
        running = true;
        updateStatus(true);
        appendLog("Đang khởi động UDP server tại " + bindHost + ":" + port + "...");
        Thread serverThread = new Thread(() -> runServer(bindHost, port), "udp-mail-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void runServer(String host, int port) {
        UdpMailServer.Listener listener = new UdpMailServer.Listener() {
            @Override public void onClientSeen(InetSocketAddress address) {
                if (knownClients.add(address)) {
                    appendLog("Phát hiện client: " + address);
                    updateStatistics();
                }
            }
            @Override public void onRequest(InetSocketAddress address) {
                requestCount.incrementAndGet();
                updateStatistics();
            }
            @Override public void onLog(String message) { appendLog(message); }
        };
        try (UdpMailServer server = new UdpMailServer(host, port,
                new MailStorageService(), listener)) {
            if (!running) {
                return;
            }
            udpServer = server;
            server.run();
        } catch (IOException exception) {
            if (running) appendLog("Không thể chạy UDP server: " + exception.getMessage());
        } finally {
            udpServer = null;
            running = false;
            updateStatus(false);
            appendLog("UDP server đã dừng.");
        }
    }

    private void stopServer() {
        running = false;
        UdpMailServer server = udpServer;
        if (server != null) {
            appendLog("Đang dừng UDP server...");
            server.close();
        }
        updateStatus(false);
    }

    private void appendLog(String message) {
        String line = "[" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "] "
                + message + System.lineSeparator();
        SwingUtilities.invokeLater(() -> {
            txtLog.append(line);
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }

    private void updateStatus(boolean online) {
        SwingUtilities.invokeLater(() -> {
            lblStatus.setText(online ? "● ONLINE • UDP" : "● OFFLINE");
            lblStatus.setForeground(online ? SUCCESS : ERROR);
            lblStatus.setBackground(online ? new Color(209, 250, 229) : new Color(254, 226, 226));
            btnStart.setEnabled(!online);
            btnStop.setEnabled(online);
            txtBindHost.setEnabled(!online);
            txtPort.setEnabled(!online);
        });
    }

    private void updateStatistics() {
        SwingUtilities.invokeLater(() -> {
            lblClientMetric.setText(String.valueOf(knownClients.size()));
            lblRequestMetric.setText(String.valueOf(requestCount.get()));
        });
    }

    private static JLabel createSmallLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(new Font("Segoe UI", Font.BOLD, 11));
        return label;
    }

    private static JLabel createNavItem(String text, boolean selected) {
        JLabel item = new JLabel(text);
        item.setAlignmentX(Component.LEFT_ALIGNMENT);
        item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        item.setOpaque(selected);
        item.setBackground(selected ? new Color(49, 46, 129) : NAVY);
        item.setForeground(selected ? Color.WHITE : new Color(148, 163, 184));
        item.setFont(new Font("Segoe UI", Font.BOLD, 12));
        item.setBorder(new EmptyBorder(12, 12, 12, 8));
        return item;
    }

    private static void styleField(JTextField field) {
        field.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        field.setForeground(TEXT);
        field.setBackground(new Color(248, 250, 252));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), new EmptyBorder(7, 9, 7, 9)));
    }

    private static JButton createButton(String text, Color background, Color foreground) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setBackground(background);
        button.setForeground(foreground);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorder(new EmptyBorder(9, 15, 9, 15));
        return button;
    }

    private static final class RoundedPanel extends JPanel {
        private final int radius;
        private final Color fill;
        private RoundedPanel(int radius, Color fill) {
            this.radius = radius;
            this.fill = fill;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new MailServerGUI().setVisible(true));
    }
}
