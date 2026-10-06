package MailClient;

import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

public class MailClientGUI extends JFrame {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 5000;
    private static final Color BACKGROUND = new Color(244, 247, 251);
    private static final Color SURFACE = Color.WHITE;
    private static final Color NAVY = new Color(15, 23, 42);
    private static final Color PRIMARY = new Color(79, 70, 229);
    private static final Color PRIMARY_LIGHT = new Color(238, 242, 255);
    private static final Color TEXT = new Color(30, 41, 59);
    private static final Color MUTED = new Color(100, 116, 139);
    private static final Color BORDER = new Color(226, 232, 240);
    private static final Color SUCCESS = new Color(5, 150, 105);
    private static final Color ERROR = new Color(220, 38, 38);
    private static final String GMAIL_PATTERN = "[a-z0-9._%+-]+@gmail\\.com";
    private static final String PAGE_INBOX = "INBOX";
    private static final String PAGE_COMPOSE = "COMPOSE";
    private static final String PAGE_SENT = "SENT";
    private static final String SCREEN_LOGIN = "LOGIN";
    private static final String SCREEN_MAIL = "MAIL";

    private final JTextField txtServerHost = new JTextField(SERVER_HOST, 11);
    private final JTextField txtServerPort = new JTextField(String.valueOf(SERVER_PORT), 5);
    private final JTextField txtUsername = new JTextField(22);
    private final JPasswordField txtPassword = new JPasswordField(22);
    private final JToggleButton btnTogglePassword = new EyeToggleButton();
    private final JTextField txtToUser = new JTextField(28);
    private final JTextField txtSubject = new JTextField(28);
    private final JTextArea txtContent = new JTextArea(5, 30);
    private final JTextArea txtMailContent = new JTextArea();
    private final JTextArea txtSentContent = new JTextArea();
    private final JLabel lblStatus = new JLabel("Chưa kết nối đến máy chủ UDP");
    private final JLabel lblLoginStatus = new JLabel("Nhập thông tin để bắt đầu");
    private final JLabel lblConnection = new JLabel("OFFLINE");
    private final JLabel lblDashboardConnection = new JLabel("● OFFLINE");
    private final JLabel lblSessionUser = new JLabel("Chưa đăng nhập");
    private final JLabel lblMailboxCount = new JLabel("0 thư");
    private final JLabel lblSentCount = new JLabel("0 thư");
    private final DefaultListModel<MailItem> mailListModel = new DefaultListModel<>();
    private final JList<MailItem> listFiles = new JList<>(mailListModel);
    private final DefaultListModel<MailItem> sentListModel = new DefaultListModel<>();
    private final JList<MailItem> listSent = new JList<>(sentListModel);
    private final JButton btnConnect = createButton("Kết nối", PRIMARY, Color.WHITE);
    private final JButton btnRegister = createButton("Tạo tài khoản", new Color(71, 85, 105), Color.WHITE);
    private final JButton btnLogin = createButton("Đăng nhập", PRIMARY, Color.WHITE);
    private final JButton btnRefresh = createButton("Làm mới", new Color(241, 245, 249), TEXT);
    private final JButton btnRefreshSent = createButton("Làm mới lịch sử",
            new Color(241, 245, 249), TEXT);
    private final JButton btnSend = createButton("Gửi thư", PRIMARY, Color.WHITE);
    private final JButton btnInboxNav = createNavButton("HỘP THƯ ĐẾN", true);
    private final JButton btnComposeNav = createNavButton("SOẠN THƯ MỚI", false);
    private final JButton btnSentNav = createNavButton("LỊCH SỬ GỬI THƯ", false);
    private final JButton btnLogoutNav = createNavButton("ĐĂNG XUẤT", false);
    private final CardLayout pageLayout = new CardLayout();
    private final JPanel pageContainer = new JPanel(pageLayout);
    private final CardLayout screenLayout = new CardLayout();
    private final JPanel screenContainer = new JPanel(screenLayout);
    private final ExecutorService networkWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "udp-mail-client-worker");
        thread.setDaemon(true);
        return thread;
    });

    private volatile UdpMailClient udpClient;
    private volatile String currentUser = "";

    public MailClientGUI() {
        super("MailFlow UDP Client");
        buildInterface();
    }

    private void buildInterface() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(980, 650));
        setSize(1180, 740);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BACKGROUND);
        setLayout(new BorderLayout());

        screenContainer.add(createLoginScreen(), SCREEN_LOGIN);
        screenContainer.add(createMailScreen(), SCREEN_MAIL);
        add(screenContainer, BorderLayout.CENTER);
        screenLayout.show(screenContainer, SCREEN_LOGIN);

        bindEvents();
    }

    private JPanel createMailScreen() {
        JPanel screen = new JPanel(new BorderLayout());
        screen.setBackground(BACKGROUND);
        screen.add(createSidebar(), BorderLayout.WEST);

        JPanel main = new JPanel(new BorderLayout(0, 18));
        main.setOpaque(false);
        main.setBorder(new EmptyBorder(22, 24, 18, 24));
        main.add(createHeader(), BorderLayout.NORTH);
        main.add(createWorkspace(), BorderLayout.CENTER);
        main.add(createStatusBar(), BorderLayout.SOUTH);
        screen.add(main, BorderLayout.CENTER);
        return screen;
    }

    private JPanel createLoginScreen() {
        JPanel screen = new JPanel(new BorderLayout());
        screen.setBackground(BACKGROUND);

        JPanel welcome = new JPanel(new GridBagLayout());
        welcome.setPreferredSize(new Dimension(410, 0));
        welcome.setBackground(NAVY);
        welcome.setBorder(new EmptyBorder(45, 42, 45, 42));
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = 0;
        left.anchor = GridBagConstraints.WEST;
        left.fill = GridBagConstraints.HORIZONTAL;
        JLabel logo = new JLabel("M", SwingConstants.CENTER);
        logo.setPreferredSize(new Dimension(58, 58));
        logo.setOpaque(true);
        logo.setBackground(PRIMARY);
        logo.setForeground(Color.WHITE);
        logo.setFont(new Font("Segoe UI", Font.BOLD, 28));
        welcome.add(logo, left);
        left.gridy++;
        left.insets = new Insets(22, 0, 0, 0);
        JLabel brand = new JLabel("MailFlow");
        brand.setForeground(Color.WHITE);
        brand.setFont(new Font("Segoe UI", Font.BOLD, 34));
        welcome.add(brand, left);
        left.gridy++;
        left.insets = new Insets(7, 0, 0, 0);
        JLabel description = new JLabel("<html><div style='width:290px'>"
                + "Quản lý hộp thư nội bộ nhanh chóng qua giao thức UDP.<br><br>"
                + "• Gửi và nhận thư trong mạng LAN<br>"
                + "• Lưu lịch sử gửi thư<br>"
                + "• Timeout và retry tự động"
                + "</div></html>");
        description.setForeground(new Color(203, 213, 225));
        description.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        welcome.add(description, left);
        left.gridy++;
        left.weighty = 1;
        welcome.add(new JLabel(), left);
        left.gridy++;
        left.weighty = 0;
        JLabel protocol = new JLabel("UDP DATAGRAM • PORT MẶC ĐỊNH 5000");
        protocol.setForeground(new Color(165, 180, 252));
        protocol.setFont(new Font("Segoe UI", Font.BOLD, 11));
        welcome.add(protocol, left);
        screen.add(welcome, BorderLayout.WEST);

        JPanel formArea = new JPanel(new GridBagLayout());
        formArea.setOpaque(false);
        RoundedPanel card = new RoundedPanel(22, SURFACE);
        card.setPreferredSize(new Dimension(520, 550));
        card.setLayout(new GridBagLayout());
        card.setBorder(new EmptyBorder(34, 42, 32, 42));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        JLabel title = new JLabel("Đăng nhập MailFlow");
        title.setForeground(TEXT);
        title.setFont(new Font("Segoe UI", Font.BOLD, 25));
        card.add(title, c);
        c.gridy++;
        c.insets = new Insets(5, 0, 22, 0);
        JLabel subtitle = new JLabel("Kết nối MailServer và mở hộp thư của bạn");
        subtitle.setForeground(MUTED);
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        card.add(subtitle, c);

        c.gridwidth = 1;
        c.insets = new Insets(5, 0, 6, 12);
        c.gridy++;
        c.gridx = 0;
        c.weightx = 0.7;
        card.add(createSmallLabel("ĐỊA CHỈ SERVER"), c);
        c.gridx = 1;
        c.weightx = 0.3;
        card.add(createSmallLabel("PORT UDP"), c);
        c.gridy++;
        c.gridx = 0;
        c.weightx = 0.7;
        styleField(txtServerHost);
        card.add(txtServerHost, c);
        c.gridx = 1;
        c.weightx = 0.3;
        styleField(txtServerPort);
        card.add(txtServerPort, c);

        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.insets = new Insets(11, 0, 8, 0);
        card.add(btnConnect, c);

        c.gridy++;
        c.insets = new Insets(15, 0, 6, 0);
        card.add(createSmallLabel("TÀI KHOẢN GMAIL"), c);
        c.gridy++;
        c.insets = new Insets(0, 0, 10, 0);
        styleField(txtUsername);
        txtUsername.setToolTipText("Ví dụ: tenban@gmail.com");
        card.add(txtUsername, c);

        c.gridy++;
        c.insets = new Insets(5, 0, 6, 0);
        card.add(createSmallLabel("MẬT KHẨU"), c);
        c.gridy++;
        c.insets = new Insets(0, 0, 10, 0);
        txtPassword.setEchoChar('\u2022');
        txtPassword.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        txtPassword.setForeground(TEXT);
        txtPassword.setBackground(new Color(248, 250, 252));
        txtPassword.setBorder(new EmptyBorder(7, 9, 7, 5));
        txtPassword.setToolTipText("Mật khẩu có ít nhất 6 ký tự");
        JPanel passwordPanel = new JPanel(new BorderLayout());
        passwordPanel.setBackground(new Color(248, 250, 252));
        passwordPanel.setBorder(BorderFactory.createLineBorder(BORDER));
        passwordPanel.add(txtPassword, BorderLayout.CENTER);
        passwordPanel.add(btnTogglePassword, BorderLayout.EAST);
        card.add(passwordPanel, c);

        JPanel accountActions = new JPanel(new GridLayout(1, 2, 10, 0));
        accountActions.setOpaque(false);
        accountActions.add(btnRegister);
        accountActions.add(btnLogin);
        c.gridy++;
        c.insets = new Insets(5, 0, 16, 0);
        card.add(accountActions, c);

        JPanel state = new JPanel(new BorderLayout(10, 0));
        state.setOpaque(false);
        lblLoginStatus.setForeground(MUTED);
        lblLoginStatus.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        lblConnection.setOpaque(true);
        lblConnection.setBackground(new Color(254, 226, 226));
        lblConnection.setForeground(ERROR);
        lblConnection.setFont(new Font("Segoe UI", Font.BOLD, 10));
        lblConnection.setBorder(new EmptyBorder(7, 10, 7, 10));
        state.add(lblLoginStatus, BorderLayout.CENTER);
        state.add(lblConnection, BorderLayout.EAST);
        c.gridy++;
        c.insets = new Insets(0, 0, 0, 0);
        card.add(state, c);

        formArea.add(card);
        screen.add(formArea, BorderLayout.CENTER);
        return screen;
    }

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(215, 0));
        sidebar.setBackground(NAVY);
        sidebar.setBorder(new EmptyBorder(26, 18, 24, 18));

        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        brand.setOpaque(false);
        JLabel logo = new JLabel("M", SwingConstants.CENTER);
        logo.setPreferredSize(new Dimension(40, 40));
        logo.setOpaque(true);
        logo.setBackground(PRIMARY);
        logo.setForeground(Color.WHITE);
        logo.setFont(new Font("Segoe UI", Font.BOLD, 21));
        JLabel brandText = new JLabel("<html><b><font color='white' size='+1'>MailFlow</font></b>"
                + "<br><font color='#94A3B8'>UDP Edition</font></html>");
        brandText.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        brand.add(logo);
        brand.add(brandText);
        sidebar.add(brand, BorderLayout.NORTH);

        JPanel navigation = new JPanel();
        navigation.setOpaque(false);
        navigation.setLayout(new javax.swing.BoxLayout(navigation, javax.swing.BoxLayout.Y_AXIS));
        JLabel menuTitle = new JLabel("KHÔNG GIAN LÀM VIỆC");
        menuTitle.setForeground(new Color(100, 116, 139));
        menuTitle.setFont(new Font("Segoe UI", Font.BOLD, 10));
        menuTitle.setBorder(new EmptyBorder(42, 10, 12, 0));
        menuTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        navigation.add(menuTitle);
        navigation.add(btnInboxNav);
        navigation.add(btnComposeNav);
        navigation.add(btnSentNav);
        navigation.add(javax.swing.Box.createVerticalStrut(18));
        btnLogoutNav.setForeground(new Color(248, 113, 113));
        navigation.add(btnLogoutNav);
        sidebar.add(navigation, BorderLayout.CENTER);

        RoundedPanel networkCard = new RoundedPanel(14, new Color(30, 41, 59));
        networkCard.setLayout(new BorderLayout(0, 5));
        networkCard.setBorder(new EmptyBorder(12, 13, 12, 13));
        JLabel protocol = new JLabel("UDP DATAGRAM");
        protocol.setForeground(new Color(165, 180, 252));
        protocol.setFont(new Font("Segoe UI", Font.BOLD, 10));
        JLabel note = new JLabel("<html><font color='#CBD5E1'>Timeout 2 giây<br>Retry tối đa 3 lần</font></html>");
        note.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        networkCard.add(protocol, BorderLayout.NORTH);
        networkCard.add(note, BorderLayout.CENTER);
        sidebar.add(networkCard, BorderLayout.SOUTH);
        return sidebar;
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(20, 0));
        header.setOpaque(false);
        JPanel titlePanel = new JPanel();
        titlePanel.setOpaque(false);
        titlePanel.setLayout(new javax.swing.BoxLayout(titlePanel, javax.swing.BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Hộp thư của bạn");
        title.setFont(new Font("Segoe UI", Font.BOLD, 27));
        title.setForeground(TEXT);
        JLabel subtitle = new JLabel("Gửi và nhận thư qua giao thức UDP");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        subtitle.setForeground(MUTED);
        titlePanel.add(title);
        titlePanel.add(subtitle);
        header.add(titlePanel, BorderLayout.WEST);

        RoundedPanel session = new RoundedPanel(16, SURFACE);
        session.setLayout(new FlowLayout(FlowLayout.RIGHT, 10, 9));
        session.setBorder(new EmptyBorder(0, 9, 0, 9));
        JLabel udpPill = new JLabel(" UDP ");
        udpPill.setOpaque(true);
        udpPill.setBackground(PRIMARY_LIGHT);
        udpPill.setForeground(PRIMARY);
        udpPill.setFont(new Font("Segoe UI", Font.BOLD, 11));
        udpPill.setBorder(new EmptyBorder(6, 8, 6, 8));
        session.add(udpPill);
        JLabel avatar = new JLabel("@", SwingConstants.CENTER);
        avatar.setPreferredSize(new Dimension(32, 32));
        avatar.setOpaque(true);
        avatar.setBackground(PRIMARY_LIGHT);
        avatar.setForeground(PRIMARY);
        avatar.setFont(new Font("Segoe UI", Font.BOLD, 16));
        session.add(avatar);
        lblSessionUser.setForeground(TEXT);
        lblSessionUser.setFont(new Font("Segoe UI", Font.BOLD, 12));
        session.add(lblSessionUser);
        lblDashboardConnection.setOpaque(true);
        lblDashboardConnection.setBackground(new Color(254, 226, 226));
        lblDashboardConnection.setForeground(ERROR);
        lblDashboardConnection.setFont(new Font("Segoe UI", Font.BOLD, 10));
        lblDashboardConnection.setBorder(new EmptyBorder(7, 10, 7, 10));
        session.add(lblDashboardConnection);
        header.add(session, BorderLayout.EAST);
        return header;
    }

    private JPanel createWorkspace() {
        JPanel workspace = new JPanel(new BorderLayout(0, 14));
        workspace.setOpaque(false);
        pageContainer.setOpaque(false);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                createInboxCard(), createReaderCard());
        split.setBorder(null);
        split.setOpaque(false);
        split.setDividerSize(12);
        split.setResizeWeight(0.32);
        split.setDividerLocation(300);
        pageContainer.add(split, PAGE_INBOX);
        pageContainer.add(createComposer(), PAGE_COMPOSE);
        pageContainer.add(createSentHistoryPage(), PAGE_SENT);
        workspace.add(pageContainer, BorderLayout.CENTER);
        return workspace;
    }

    private JPanel createInboxCard() {
        RoundedPanel inbox = new RoundedPanel(18, SURFACE);
        inbox.setLayout(new BorderLayout(0, 12));
        inbox.setBorder(new EmptyBorder(17, 16, 16, 16));
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        JLabel title = new JLabel("Thư đến");
        title.setFont(new Font("Segoe UI", Font.BOLD, 17));
        title.setForeground(TEXT);
        lblMailboxCount.setForeground(MUTED);
        lblMailboxCount.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        heading.add(title, BorderLayout.WEST);
        heading.add(lblMailboxCount, BorderLayout.EAST);
        inbox.add(heading, BorderLayout.NORTH);

        listFiles.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        listFiles.setFixedCellHeight(52);
        listFiles.setBackground(SURFACE);
        listFiles.setSelectionBackground(PRIMARY_LIGHT);
        listFiles.setSelectionForeground(TEXT);
        listFiles.setBorder(new EmptyBorder(2, 0, 2, 0));
        listFiles.setCellRenderer(new MailCellRenderer());
        JScrollPane scroll = new JScrollPane(listFiles);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, BORDER));
        scroll.getViewport().setBackground(SURFACE);
        inbox.add(scroll, BorderLayout.CENTER);
        inbox.add(btnRefresh, BorderLayout.SOUTH);
        return inbox;
    }

    private JPanel createReaderCard() {
        RoundedPanel reader = new RoundedPanel(18, SURFACE);
        reader.setLayout(new BorderLayout(0, 10));
        reader.setBorder(new EmptyBorder(17, 18, 16, 18));
        JLabel readerTitle = new JLabel("Nội dung thư");
        readerTitle.setForeground(TEXT);
        readerTitle.setFont(new Font("Segoe UI", Font.BOLD, 17));
        reader.add(readerTitle, BorderLayout.NORTH);
        txtMailContent.setEditable(false);
        txtMailContent.setLineWrap(true);
        txtMailContent.setWrapStyleWord(true);
        txtMailContent.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        txtMailContent.setForeground(TEXT);
        txtMailContent.setBackground(new Color(250, 251, 253));
        txtMailContent.setBorder(new EmptyBorder(14, 14, 14, 14));
        txtMailContent.setText("Chọn một thư ở danh sách bên trái để xem nội dung.");
        JScrollPane mailScroll = new JScrollPane(txtMailContent);
        mailScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        reader.add(mailScroll, BorderLayout.CENTER);
        return reader;
    }

    private JPanel createComposer() {
        RoundedPanel composer = new RoundedPanel(18, SURFACE);
        composer.setLayout(new GridBagLayout());
        composer.setBorder(new EmptyBorder(25, 28, 25, 28));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 0, 10, 14);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.gridy = 0;
        JLabel title = new JLabel("Soạn thư mới");
        title.setFont(new Font("Segoe UI", Font.BOLD, 22));
        title.setForeground(TEXT);
        composer.add(title, c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        JLabel hint = new JLabel("Điền đầy đủ thông tin trước khi gửi");
        hint.setForeground(MUTED);
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        composer.add(hint, c);
        c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.gridy = 1;
        composer.add(createSmallLabel("Người nhận Gmail"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        styleField(txtToUser);
        txtToUser.setToolTipText("Ví dụ: nguoinhan@gmail.com");
        composer.add(txtToUser, c);
        c.gridx = 0; c.gridy = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        composer.add(createSmallLabel("Tiêu đề thư"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        styleField(txtSubject);
        txtSubject.setToolTipText("Nhập tiêu đề thư");
        composer.add(txtSubject, c);
        c.gridx = 0; c.gridy = 3; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        composer.add(createSmallLabel("Nội dung"), c);
        c.gridx = 1; c.weightx = 1; c.weighty = 1; c.fill = GridBagConstraints.BOTH;
        txtContent.setLineWrap(true);
        txtContent.setWrapStyleWord(true);
        txtContent.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        txtContent.setForeground(TEXT);
        txtContent.setBackground(new Color(250, 251, 253));
        txtContent.setBorder(new EmptyBorder(8, 10, 8, 10));
        JScrollPane contentScroll = new JScrollPane(txtContent);
        contentScroll.setPreferredSize(new Dimension(500, 260));
        contentScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        composer.add(contentScroll, c);
        c.gridx = 2; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.SOUTHEAST;
        composer.add(btnSend, c);
        return composer;
    }

    private JPanel createSentHistoryPage() {
        JPanel page = new JPanel(new BorderLayout());
        page.setOpaque(false);

        RoundedPanel history = new RoundedPanel(18, SURFACE);
        history.setLayout(new BorderLayout(0, 12));
        history.setBorder(new EmptyBorder(17, 16, 16, 16));
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        JLabel title = new JLabel("Thư đã gửi");
        title.setFont(new Font("Segoe UI", Font.BOLD, 17));
        title.setForeground(TEXT);
        lblSentCount.setForeground(MUTED);
        lblSentCount.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        heading.add(title, BorderLayout.WEST);
        heading.add(lblSentCount, BorderLayout.EAST);
        history.add(heading, BorderLayout.NORTH);

        listSent.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        listSent.setFixedCellHeight(58);
        listSent.setBackground(SURFACE);
        listSent.setSelectionBackground(PRIMARY_LIGHT);
        listSent.setSelectionForeground(TEXT);
        listSent.setBorder(new EmptyBorder(2, 0, 2, 0));
        listSent.setCellRenderer(new MailCellRenderer());
        JScrollPane sentScroll = new JScrollPane(listSent);
        sentScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, BORDER));
        sentScroll.getViewport().setBackground(SURFACE);
        history.add(sentScroll, BorderLayout.CENTER);
        history.add(btnRefreshSent, BorderLayout.SOUTH);

        RoundedPanel reader = new RoundedPanel(18, SURFACE);
        reader.setLayout(new BorderLayout(0, 10));
        reader.setBorder(new EmptyBorder(17, 18, 16, 18));
        JLabel readerTitle = new JLabel("Nội dung thư đã gửi");
        readerTitle.setForeground(TEXT);
        readerTitle.setFont(new Font("Segoe UI", Font.BOLD, 17));
        reader.add(readerTitle, BorderLayout.NORTH);
        txtSentContent.setEditable(false);
        txtSentContent.setLineWrap(true);
        txtSentContent.setWrapStyleWord(true);
        txtSentContent.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        txtSentContent.setForeground(TEXT);
        txtSentContent.setBackground(new Color(250, 251, 253));
        txtSentContent.setBorder(new EmptyBorder(14, 14, 14, 14));
        txtSentContent.setText("Chọn một thư đã gửi để xem lại nội dung.");
        JScrollPane contentScroll = new JScrollPane(txtSentContent);
        contentScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        reader.add(contentScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, history, reader);
        split.setBorder(null);
        split.setOpaque(false);
        split.setDividerSize(12);
        split.setResizeWeight(0.36);
        split.setDividerLocation(340);
        page.add(split, BorderLayout.CENTER);
        return page;
    }

    private JPanel createStatusBar() {
        JPanel status = new JPanel(new BorderLayout());
        status.setOpaque(false);
        lblStatus.setForeground(MUTED);
        lblStatus.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        status.add(lblStatus, BorderLayout.WEST);
        JLabel limit = new JLabel("Giới hạn gói ứng dụng: 60 KB");
        limit.setForeground(MUTED);
        limit.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        status.add(limit, BorderLayout.EAST);
        return status;
    }

    private void bindEvents() {
        btnConnect.addActionListener(event -> connectToServer());
        btnRegister.addActionListener(event -> register());
        btnLogin.addActionListener(event -> login());
        btnRefresh.addActionListener(event -> login());
        btnRefreshSent.addActionListener(event -> loadSentHistory());
        btnSend.addActionListener(event -> sendMail());
        btnTogglePassword.addActionListener(event -> {
            boolean visible = btnTogglePassword.isSelected();
            txtPassword.setEchoChar(visible ? (char) 0 : '\u2022');
            btnTogglePassword.setToolTipText(visible ? "Ẩn mật khẩu" : "Hiện mật khẩu");
            btnTogglePassword.repaint();
        });
        btnInboxNav.addActionListener(event -> showPage(PAGE_INBOX, btnInboxNav));
        btnComposeNav.addActionListener(event -> showPage(PAGE_COMPOSE, btnComposeNav));
        btnSentNav.addActionListener(event -> {
            showPage(PAGE_SENT, btnSentNav);
            loadSentHistory();
        });
        btnLogoutNav.addActionListener(event -> logout());
        listFiles.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelectedMail(listFiles.getSelectedValue());
        });
        listSent.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelectedSentMail(listSent.getSelectedValue());
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) {
                closeConnection();
                networkWorker.shutdownNow();
                dispose();
            }
        });
    }

    private void connectToServer() {
        String host = txtServerHost.getText().trim();
        if (host.isEmpty()) {
            setStatus("Vui lòng nhập IP hoặc tên máy chủ", true);
            return;
        }
        final int port;
        try {
            port = Integer.parseInt(txtServerPort.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            setStatus("Port phải là số nguyên từ 1 đến 65535", true);
            return;
        }
        setBusy(true, "Đang kiểm tra UDP server...");
        networkWorker.submit(() -> {
            UdpMailClient candidate = null;
            try {
                candidate = new UdpMailClient(host, port);
                if (!candidate.ping()) throw new IOException("Phản hồi PING không hợp lệ");
                closeConnection();
                udpClient = candidate;
                SwingUtilities.invokeLater(() -> {
                    setBusy(false, "Đã kết nối UDP tới " + host + ":" + port);
                    setConnectionState(true);
                });
            } catch (IOException exception) {
                if (candidate != null) candidate.close();
                String message = exception.getMessage();
                SwingUtilities.invokeLater(() -> {
                    setBusy(false, "Không thể kết nối: " + message);
                    setStatus("Không thể kết nối UDP: " + message, true);
                    setConnectionState(false);
                });
            }
        });
    }

    private void register() {
        String username = normalizeEmail(txtUsername.getText());
        if (!validUsername(username)) return;
        String password = readPassword();
        if (!validPassword(password)) return;
        txtUsername.setText(username);
        requestAsync("REGISTER " + username + " " + encodePassword(password), response -> {
            boolean error = !"REGISTER_SUCCESS".equals(response);
            if (response.startsWith("REGISTER_FAILED")) {
                setStatus("Tài khoản đã có mật khẩu hoặc thông tin không hợp lệ", true);
            } else {
                setStatus(error ? response
                        : "Tạo tài khoản hoặc thiết lập mật khẩu lần đầu thành công", error);
            }
        });
    }

    private void login() {
        String username = normalizeEmail(txtUsername.getText());
        if (!validUsername(username)) return;
        String password = readPassword();
        if (!validPassword(password)) return;
        txtUsername.setText(username);
        requestAsync("LOGIN " + username + " " + encodePassword(password), response -> {
            if (!response.startsWith("LOGIN_SUCCESS|")) {
                setStatus("Tài khoản Gmail hoặc mật khẩu không chính xác", true);
                return;
            }
            updateMailList(response);
            currentUser = username;
            resetPasswordField();
            lblSessionUser.setText(username);
            setStatus("Đã tải " + mailListModel.size() + " thư của " + username, false);
            showPage(PAGE_INBOX, btnInboxNav);
            screenLayout.show(screenContainer, SCREEN_MAIL);
        });
    }

    private boolean validUsername(String username) {
        if (username.matches(GMAIL_PATTERN)) return true;
        setStatus("Vui lòng nhập địa chỉ Gmail hợp lệ, ví dụ: tenban@gmail.com", true);
        return false;
    }

    private String normalizeEmail(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String readPassword() {
        char[] characters = txtPassword.getPassword();
        try {
            return new String(characters);
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    private boolean validPassword(String password) {
        if (password.length() >= 6 && password.length() <= 128) return true;
        setStatus("Mật khẩu phải có từ 6 đến 128 ký tự", true);
        return false;
    }

    private String encodePassword(String password) {
        return Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8));
    }

    private void updateMailList(String response) {
        parseMailList(response, "LOGIN_SUCCESS|", mailListModel);
        lblMailboxCount.setText(mailListModel.size() + " thư");
        if (mailListModel.isEmpty()) {
            txtMailContent.setText("Hộp thư chưa có nội dung.");
        }
    }

    private void loadSentHistory() {
        String username = currentUser;
        if (!validUsername(username)) return;
        requestAsync("SENT " + username, response -> {
            if (!response.startsWith("SENT_SUCCESS|")) {
                setStatus("Không thể tải lịch sử gửi thư", true);
                return;
            }
            parseMailList(response, "SENT_SUCCESS|", sentListModel);
            lblSentCount.setText(sentListModel.size() + " thư");
            txtSentContent.setText(sentListModel.isEmpty()
                    ? "Bạn chưa gửi thư nào." : "Chọn một thư đã gửi để xem lại nội dung.");
            setStatus("Đã tải " + sentListModel.size() + " thư đã gửi", false);
        });
    }

    private void parseMailList(String response, String prefix,
            DefaultListModel<MailItem> targetModel) {
        targetModel.clear();
        String entries = response.substring(prefix.length());
        if (entries.isEmpty()) return;
        for (String entry : entries.split(",")) {
            if (entry.isEmpty()) continue;
            String[] fields = entry.split(";", 3);
            if (fields.length == 3) {
                try {
                    String subject = new String(Base64.getDecoder().decode(fields[1]),
                            StandardCharsets.UTF_8);
                    long timestamp = Long.parseLong(fields[2]);
                    targetModel.addElement(new MailItem(fields[0], subject, timestamp));
                    continue;
                } catch (IllegalArgumentException exception) {
                    // Đọc theo định dạng cũ bên dưới nếu metadata bị lỗi.
                }
            }
            targetModel.addElement(new MailItem(entry, "(Không có tiêu đề)", 0));
        }
    }

    private void sendMail() {
        String sender = currentUser;
        String recipient = normalizeEmail(txtToUser.getText());
        String subject = txtSubject.getText().trim();
        String content = txtContent.getText();
        if (!validUsername(sender)) {
            setStatus("Hãy nhập tài khoản Gmail của bạn ở thanh phía trên", true);
            return;
        }
        if (!recipient.matches(GMAIL_PATTERN)) {
            setStatus("Người nhận phải là địa chỉ Gmail hợp lệ", true);
            return;
        }
        if (subject.isEmpty() || content.trim().isEmpty()) {
            setStatus("Vui lòng nhập đầy đủ tiêu đề và nội dung thư", true);
            return;
        }
        txtToUser.setText(recipient);
        String formattedMail = "Người gửi: " + sender + "\n"
                + "Người nhận: " + recipient + "\n"
                + "Tiêu đề: " + subject + "\n\n" + content;
        String encoded = Base64.getEncoder().encodeToString(
                formattedMail.getBytes(StandardCharsets.UTF_8));
        requestAsync("SEND " + recipient + " " + encoded, response -> {
            boolean error = !"SEND_SUCCESS".equals(response);
            if (response.startsWith("SEND_FAILED")) {
                setStatus("Tài khoản người gửi hoặc người nhận chưa đăng ký trên MailServer", true);
            } else {
                setStatus(error ? response : "Đã gửi thư tới " + recipient, error);
            }
            if (!error) {
                txtSubject.setText("");
                txtContent.setText("");
            }
        });
    }

    private void showSelectedMail(MailItem mail) {
        if (mail == null) return;
        String username = currentUser;
        if (!validUsername(username)) return;
        requestAsync("READ " + username + " " + mail.fileName, response -> {
            if (!response.startsWith("MAIL_CONTENT|")) {
                setStatus("Không thể đọc thư: " + response, true);
                return;
            }
            try {
                txtMailContent.setText(new String(Base64.getDecoder().decode(
                        response.substring("MAIL_CONTENT|".length())), StandardCharsets.UTF_8));
                txtMailContent.setCaretPosition(0);
                setStatus("Đang xem: " + mail.subject, false);
            } catch (IllegalArgumentException exception) {
                setStatus("Nội dung thư không hợp lệ", true);
            }
        });
    }

    private void showSelectedSentMail(MailItem mail) {
        if (mail == null) return;
        String username = currentUser;
        if (!validUsername(username)) return;
        requestAsync("READ_SENT " + username + " " + mail.fileName, response -> {
            if (!response.startsWith("MAIL_CONTENT|")) {
                setStatus("Không thể đọc thư đã gửi: " + response, true);
                return;
            }
            try {
                txtSentContent.setText(new String(Base64.getDecoder().decode(
                        response.substring("MAIL_CONTENT|".length())), StandardCharsets.UTF_8));
                txtSentContent.setCaretPosition(0);
                setStatus("Đang xem thư đã gửi: " + mail.subject, false);
            } catch (IllegalArgumentException exception) {
                setStatus("Nội dung thư đã gửi không hợp lệ", true);
            }
        });
    }

    private void requestAsync(String request, Consumer<String> onSuccess) {
        UdpMailClient client = udpClient;
        if (client == null || client.isClosed()) {
            setStatus("Hãy kết nối tới UDP MailServer trước", true);
            return;
        }
        setBusy(true, "Đang gửi yêu cầu UDP...");
        networkWorker.submit(() -> {
            try {
                String response = client.sendRequest(request);
                SwingUtilities.invokeLater(() -> {
                    setBusy(false, "Đã nhận phản hồi từ server");
                    onSuccess.accept(response);
                });
            } catch (IOException exception) {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false, "Yêu cầu thất bại");
                    setStatus(exception.getMessage(), true);
                    if (client.isClosed()) setConnectionState(false);
                });
            }
        });
    }

    private void setBusy(boolean busy, String message) {
        btnConnect.setEnabled(!busy);
        btnRegister.setEnabled(!busy);
        btnLogin.setEnabled(!busy);
        btnRefresh.setEnabled(!busy);
        btnRefreshSent.setEnabled(!busy);
        btnSend.setEnabled(!busy);
        setStatus(message, false);
    }

    private void setConnectionState(boolean online) {
        lblConnection.setText(online ? "● ONLINE" : "● OFFLINE");
        lblConnection.setForeground(online ? SUCCESS : ERROR);
        lblConnection.setBackground(online ? new Color(209, 250, 229) : new Color(254, 226, 226));
        lblDashboardConnection.setText(online ? "● ONLINE" : "● OFFLINE");
        lblDashboardConnection.setForeground(online ? SUCCESS : ERROR);
        lblDashboardConnection.setBackground(online
                ? new Color(209, 250, 229) : new Color(254, 226, 226));
    }

    private void setStatus(String message, boolean error) {
        lblStatus.setText((error ? "●  " : "✓  ") + message);
        lblStatus.setForeground(error ? ERROR : MUTED);
        lblLoginStatus.setText(message);
        lblLoginStatus.setForeground(error ? ERROR : MUTED);
    }

    private void logout() {
        closeConnection();
        currentUser = "";
        lblSessionUser.setText("Chưa đăng nhập");
        txtUsername.setText("");
        resetPasswordField();
        txtToUser.setText("");
        txtSubject.setText("");
        txtContent.setText("");
        mailListModel.clear();
        sentListModel.clear();
        lblMailboxCount.setText("0 thư");
        lblSentCount.setText("0 thư");
        txtMailContent.setText("Chọn một thư ở danh sách bên trái để xem nội dung.");
        txtSentContent.setText("Chọn một thư đã gửi để xem lại nội dung.");
        setConnectionState(false);
        showPage(PAGE_INBOX, btnInboxNav);
        setStatus("Đã đăng xuất. Vui lòng kết nối và đăng nhập lại.", false);
        screenLayout.show(screenContainer, SCREEN_LOGIN);
    }

    private void resetPasswordField() {
        txtPassword.setText("");
        txtPassword.setEchoChar('\u2022');
        btnTogglePassword.setSelected(false);
        btnTogglePassword.setToolTipText("Hiện mật khẩu");
        btnTogglePassword.repaint();
    }

    private void closeConnection() {
        UdpMailClient current = udpClient;
        udpClient = null;
        if (current != null) current.close();
    }

    private static JLabel createSmallLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(new Font("Segoe UI", Font.BOLD, 11));
        return label;
    }

    private void showPage(String page, JButton selectedButton) {
        pageLayout.show(pageContainer, page);
        styleNavButton(btnInboxNav, selectedButton == btnInboxNav);
        styleNavButton(btnComposeNav, selectedButton == btnComposeNav);
        styleNavButton(btnSentNav, selectedButton == btnSentNav);
    }

    private static JButton createNavButton(String text, boolean selected) {
        JButton item = new JButton(text);
        item.setAlignmentX(Component.LEFT_ALIGNMENT);
        item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        item.setHorizontalAlignment(SwingConstants.LEFT);
        item.setFocusPainted(false);
        item.setBorderPainted(false);
        item.setContentAreaFilled(true);
        item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        styleNavButton(item, selected);
        return item;
    }

    private static void styleNavButton(JButton item, boolean selected) {
        item.setOpaque(true);
        item.setBackground(selected ? new Color(49, 46, 129) : NAVY);
        item.setForeground(selected ? Color.WHITE : new Color(148, 163, 184));
        item.setFont(new Font("Segoe UI", Font.BOLD, 12));
        item.setBorder(new EmptyBorder(12, 12, 12, 8));
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

    private static final class EyeToggleButton extends JToggleButton {
        private EyeToggleButton() {
            setPreferredSize(new Dimension(42, 32));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Hiện mật khẩu");
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(248, 250, 252));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(isSelected() ? PRIMARY : MUTED);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            int centerX = getWidth() / 2;
            int centerY = getHeight() / 2;
            g2.drawArc(centerX - 12, centerY - 8, 24, 16, 0, 180);
            g2.drawArc(centerX - 12, centerY - 8, 24, 16, 180, 180);
            g2.fillOval(centerX - 3, centerY - 3, 6, 6);
            if (!isSelected()) {
                g2.setColor(new Color(148, 163, 184));
                g2.drawLine(centerX - 12, centerY - 10, centerX + 12, centerY + 10);
            }
            g2.dispose();
        }
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

    private static final class MailCellRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, selected, focus);
            MailItem mail = (MailItem) value;
            String time = mail.timestamp > 0
                    ? new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(mail.timestamp))
                    : "Chưa có thời gian";
            label.setText("<html><b>" + escapeHtml(mail.subject)
                    + "</b><br><font color='#64748B'>" + time + "</font></html>");
            label.setFont(new Font("Segoe UI", Font.PLAIN, 12));
            label.setBorder(new EmptyBorder(5, 10, 5, 8));
            label.setBackground(selected ? PRIMARY_LIGHT : SURFACE);
            label.setForeground(TEXT);
            return label;
        }

        private String escapeHtml(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private static final class MailItem {
        private final String fileName;
        private final String subject;
        private final long timestamp;

        private MailItem(String fileName, String subject, long timestamp) {
            this.fileName = fileName;
            this.subject = subject;
            this.timestamp = timestamp;
        }

        @Override public String toString() { return subject; }
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> new MailClientGUI().setVisible(true));
    }
}
