pragma ComponentBehavior: Bound

import QtQuick
import QtQuick.Layouts
import Quickshell
import Quickshell.Widgets
import M3Shapes
import Caelestia.Config
import Caelestia.I18n
import qs.components
import qs.components.controls
import qs.components.containers
import qs.services

Item {
    id: root

    property string databaseUrl: "https://smart-desk-companion-cf7fd-default-rtdb.asia-southeast1.firebasedatabase.app"
    property string tasksNode: "tasks"

    property var allTasks: []
    property var pendingTasks: []
    property var completedTasks: []
    property bool showCompleted: false
    property bool isLoading: false
    property bool isConnected: true
    property string statusText: Tr.tr("Synced with Firebase")

    implicitWidth: 840
    implicitHeight: 420

    readonly property var subjectCatalog: ({
        "computer_networks": { name: "Computer Networks", icon: "router" },
        "cn": { name: "Computer Networks", icon: "router" },
        "probability_statistics": { name: "Probability and Statistics", icon: "bar_chart" },
        "prob_stats": { name: "Probability and Statistics", icon: "bar_chart" },
        "operating_systems": { name: "Operating Systems", icon: "memory" },
        "os": { name: "Operating Systems", icon: "memory" },
        "oop": { name: "Object Oriented Programming", icon: "code" },
        "cloud_engineering": { name: "Cloud Engineering", icon: "cloud" },
        "cloud_eng": { name: "Cloud Engineering", icon: "cloud" },
        "general_management": { name: "General Management Minor", icon: "business_center" },
        "gen_mgmt_minor": { name: "General Management Minor", icon: "business_center" },
        "capstone_project": { name: "Capstone Project", icon: "rocket_launch" },
        "capstone": { name: "Capstone Project", icon: "rocket_launch" },
        "placement_training": { name: "Placement Training", icon: "badge" },
        "placement": { name: "Placement Training", icon: "badge" },
        "mentoring_hed": { name: "Mentoring & HED", icon: "groups" },
        "performance_session": { name: "Live Performance", icon: "music_note" },
        "others": { name: "Others", icon: "assignment" }
    })

    function cleanBaseUrl(): string {
        return root.databaseUrl.replace(/\/+$/, "");
    }

    function resolveSubject(subjectId: string): var {
        const key = (subjectId || "others").trim();
        return root.subjectCatalog[key] || root.subjectCatalog["others"];
    }

    function parseIsoDateMidnightMs(isoStr: string): real {
        if (!isoStr || typeof isoStr !== "string")
            return NaN;
        const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoStr.trim());
        if (!m)
            return NaN;
        const y = Number(m[1]);
        const mo = Number(m[2]) - 1;
        const d = Number(m[3]);
        const dt = new Date(y, mo, d, 0, 0, 0, 0);
        if (dt.getFullYear() !== y || dt.getMonth() !== mo || dt.getDate() !== d)
            return NaN;
        return dt.getTime();
    }

    function formatShortDeadline(ms: real): string {
        const days = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
        const months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
        const dt = new Date(ms);
        return `${days[dt.getDay()]}, ${dt.getDate()} ${months[dt.getMonth()]}`;
    }

    function evaluateDeadline(deadlineIso: string): var {
        const targetMs = parseIsoDateMidnightMs(deadlineIso);
        if (isNaN(targetMs)) {
            return {
                label: deadlineIso || "No date",
                isUrgentRed: false,
                daysUntil: 999999,
                sortMs: 9007199254740991
            };
        }

        const now = new Date();
        const todayMidnightMs = new Date(now.getFullYear(), now.getMonth(), now.getDate(), 0, 0, 0, 0).getTime();
        const daysUntil = Math.round((targetMs - todayMidnightMs) / 86400000);

        let label = "";
        if (daysUntil < 0)
            label = Tr.tr("Overdue");
        else if (daysUntil === 0)
            label = Tr.tr("Today");
        else if (daysUntil === 1)
            label = Tr.tr("Tomorrow");
        else
            label = formatShortDeadline(targetMs);

        return {
            label: label,
            isUrgentRed: daysUntil <= 1,
            daysUntil: daysUntil,
            sortMs: targetMs
        };
    }

    function recomputeLists(rawList: var): void {
        const enriched = [];
        for (let i = 0; i < rawList.length; i++) {
            const t = rawList[i];
            if (!t || !t.title || !String(t.title).trim())
                continue;
            const subInfo = resolveSubject(t.subjectId);
            const dlInfo = evaluateDeadline(t.deadline);
            enriched.push({
                id: String(t.id),
                title: String(t.title).trim(),
                subjectId: String(t.subjectId || "others").trim(),
                subjectName: subInfo.name,
                subjectIcon: subInfo.icon,
                isPerformanceSession: String(t.subjectId || "").trim() === "performance_session",
                deadline: String(t.deadline || ""),
                deadlineLabel: dlInfo.label,
                isUrgentRed: dlInfo.isUrgentRed,
                sortMs: dlInfo.sortMs,
                completed: t.completed === true,
                createdAt: Number(t.createdAt || 0),
                completedAt: t.completedAt ? Number(t.completedAt) : null
            });
        }

        const pending = enriched.filter(item => !item.completed);
        pending.sort((a, b) => {
            if (a.sortMs !== b.sortMs)
                return a.sortMs - b.sortMs;
            return a.createdAt - b.createdAt;
        });

        const completed = enriched.filter(item => item.completed);
        completed.sort((a, b) => {
            const aTime = a.completedAt || a.createdAt || 0;
            const bTime = b.completedAt || b.createdAt || 0;
            return bTime - aTime;
        });

        root.allTasks = enriched;
        root.pendingTasks = pending;
        root.completedTasks = completed;
    }

    function parseTasksSnapshot(data: var): void {
        if (!data || typeof data !== "object") {
            root.recomputeLists([]);
            return;
        }

        const rawList = [];
        const keys = Object.keys(data);
        for (let i = 0; i < keys.length; i++) {
            const k = keys[i];
            const item = data[k];
            if (!item || typeof item !== "object")
                continue;
            const title = typeof item.title === "string" ? item.title.trim() : "";
            if (!title)
                continue;
            rawList.push({
                id: (item.id && String(item.id).trim()) ? String(item.id).trim() : k,
                title: title,
                subjectId: (item.subjectId && String(item.subjectId).trim()) ? String(item.subjectId).trim() : "others",
                deadline: item.deadline ? String(item.deadline).trim() : "",
                completed: item.completed === true,
                createdAt: Number(item.createdAt || 0),
                completedAt: item.completedAt ? Number(item.completedAt) : null
            });
        }

        root.recomputeLists(rawList);
    }

    function fetchTasks(): void {
        const xhr = new XMLHttpRequest();
        const url = `${cleanBaseUrl()}/${root.tasksNode}.json`;
        xhr.open("GET", url, true);
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== XMLHttpRequest.DONE)
                return;
            root.isLoading = false;
            if (xhr.status >= 200 && xhr.status < 300) {
                root.isConnected = true;
                try {
                    const parsed = xhr.responseText && xhr.responseText.trim() ? JSON.parse(xhr.responseText) : null;
                    root.parseTasksSnapshot(parsed);
                    root.statusText = Tr.tr("Live • %1 pending").arg(root.pendingTasks.length);
                } catch (e) {
                    root.isConnected = false;
                    root.statusText = Tr.tr("Parse error");
                }
            } else {
                root.isConnected = false;
                root.statusText = Tr.tr("Offline (HTTP %1)").arg(xhr.status);
            }
        };
        root.isLoading = true;
        xhr.send();
    }

    function toggleTaskCompleted(taskItem: var, newCompleted: bool): void {
        if (!taskItem || !taskItem.id)
            return;

        const nowMs = newCompleted ? Date.now() : null;

        // Optimistic local state update
        const updatedRaw = root.allTasks.map(existing => {
            if (existing.id === taskItem.id) {
                return {
                    id: existing.id,
                    title: existing.title,
                    subjectId: existing.subjectId,
                    deadline: existing.deadline,
                    completed: newCompleted,
                    createdAt: existing.createdAt,
                    completedAt: nowMs
                };
            }
            return existing;
        });
        root.recomputeLists(updatedRaw);
        root.statusText = Tr.tr("Live • %1 pending").arg(root.pendingTasks.length);

        const payload = {
            id: taskItem.id,
            title: taskItem.title,
            subjectId: taskItem.subjectId,
            deadline: taskItem.deadline,
            completed: newCompleted,
            createdAt: taskItem.createdAt,
            completedAt: nowMs
        };

        const xhr = new XMLHttpRequest();
        const url = `${cleanBaseUrl()}/${root.tasksNode}/${encodeURIComponent(taskItem.id)}.json`;
        xhr.open("PUT", url, true);
        xhr.setRequestHeader("Content-Type", "application/json");
        xhr.onreadystatechange = function () {
            if (xhr.readyState === XMLHttpRequest.DONE)
                root.fetchTasks();
        };
        xhr.send(JSON.stringify(payload));
    }

    Component.onCompleted: root.fetchTasks()

    onVisibleChanged: {
        if (visible)
            root.fetchTasks();
    }

    Timer {
        id: pollTimer

        interval: 4000
        repeat: true
        running: root.visible
        onTriggered: root.fetchTasks()
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: Tokens.padding.medium
        spacing: Tokens.spacing.medium

        // Top Header Bar
        RowLayout {
            Layout.fillWidth: true
            spacing: Tokens.spacing.medium

            StyledRect {
                implicitWidth: 42
                implicitHeight: 42
                radius: Tokens.rounding.medium
                color: Colours.palette.m3primaryContainer

                MaterialIcon {
                    anchors.centerIn: parent
                    text: "task_alt"
                    fontStyle: Tokens.font.icon.large
                    color: Colours.palette.m3onPrimaryContainer
                    fill: 1
                }
            }

            ColumnLayout {
                spacing: 2

                RowLayout {
                    spacing: Tokens.spacing.small

                    StyledText {
                        text: Tr.tr("Tasks & Deadlines")
                        font: Tokens.font.title.builders.medium.weight(Font.DemiBold).build()
                        color: Colours.palette.m3onSurface
                    }

                    StyledRect {
                        visible: root.pendingTasks.length > 0
                        implicitWidth: pendingCountText.implicitWidth + Tokens.padding.small * 2
                        implicitHeight: pendingCountText.implicitHeight + 4
                        radius: Tokens.rounding.full
                        color: Colours.palette.m3primary

                        StyledText {
                            id: pendingCountText

                            anchors.centerIn: parent
                            text: String(root.pendingTasks.length)
                            font: Tokens.font.body.builders.small.weight(Font.Bold).build()
                            color: Colours.palette.m3onPrimary
                        }
                    }
                }

                RowLayout {
                    spacing: Tokens.spacing.extraSmall

                    StyledRect {
                        implicitWidth: 8
                        implicitHeight: 8
                        radius: Tokens.rounding.full
                        color: root.isConnected ? Colours.palette.m3primary : Colours.palette.m3error
                    }

                    StyledText {
                        text: root.statusText
                        font: Tokens.font.body.small
                        color: Colours.palette.m3onSurfaceVariant
                    }
                }
            }

            Item {
                Layout.fillWidth: true
            }

            IconButton {
                icon: "refresh"
                type: IconButton.Tonal
                isRound: true
                onClicked: root.fetchTasks()
            }
        }

        // Main Body: Empty State or Scrollable Tasks List
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            // Empty State (when no pending tasks and no completed tasks)
            Loader {
                anchors.centerIn: parent
                active: root.pendingTasks.length === 0 && root.completedTasks.length === 0
                asynchronous: true

                sourceComponent: ColumnLayout {
                    spacing: Tokens.spacing.small

                    MaterialShape {
                        Layout.alignment: Qt.AlignHCenter
                        color: Colours.palette.m3surfaceContainerHigh
                        implicitSize: emptyTaskIcon.implicitHeight + Tokens.padding.extraLarge * 1.6
                        shape: MaterialShape.ClamShell

                        MaterialIcon {
                            id: emptyTaskIcon

                            anchors.centerIn: parent
                            text: "check_circle"
                            fontStyle: Tokens.font.icon.builders.extraLarge.scale(1.6).build()
                            color: Colours.palette.m3onSurfaceVariant
                        }
                    }

                    StyledText {
                        Layout.alignment: Qt.AlignHCenter
                        text: Tr.tr("No pending tasks")
                        font: Tokens.font.title.large
                        color: Colours.palette.m3onSurface
                    }

                    StyledText {
                        Layout.alignment: Qt.AlignHCenter
                        text: Tr.tr("Add tasks from your phone or via Alexa to see them here")
                        font: Tokens.font.body.medium
                        color: Colours.palette.m3onSurfaceVariant
                    }
                }
            }

            // Scrollable Pending + Collapsible Completed Tasks
            ClippingRectangle {
                anchors.fill: parent
                visible: root.pendingTasks.length > 0 || root.completedTasks.length > 0
                radius: Tokens.rounding.large
                color: "transparent"

                StyledFlickable {
                    id: tasksFlickable

                    anchors.fill: parent
                    anchors.rightMargin: tasksScrollBar.implicitWidth + Tokens.spacing.extraSmall
                    flickableDirection: Flickable.VerticalFlick
                    contentWidth: width
                    contentHeight: tasksColumn.implicitHeight

                    ColumnLayout {
                        id: tasksColumn

                        width: tasksFlickable.width
                        spacing: Tokens.spacing.small

                        // Pending Tasks Empty Notice (when only completed tasks exist)
                        StyledRect {
                            visible: root.pendingTasks.length === 0
                            Layout.fillWidth: true
                            implicitHeight: 54
                            radius: Tokens.rounding.large
                            color: Colours.tPalette.m3surfaceContainer

                            RowLayout {
                                anchors.centerIn: parent
                                spacing: Tokens.spacing.small

                                MaterialIcon {
                                    text: "celebration"
                                    fontStyle: Tokens.font.icon.medium
                                    color: Colours.palette.m3primary
                                }

                                StyledText {
                                    text: Tr.tr("All pending tasks are complete!")
                                    font: Tokens.font.body.medium
                                    color: Colours.palette.m3onSurfaceVariant
                                }
                            }
                        }

                        // Pending Tasks Repeater
                        Repeater {
                            model: ScriptModel {
                                values: root.pendingTasks
                            }

                            delegate: TaskRowCard {
                                isCompletedRow: false
                            }
                        }

                        // Collapsible Completed Tasks Section
                        StyledRect {
                            visible: root.completedTasks.length > 0
                            Layout.fillWidth: true
                            Layout.topMargin: Tokens.spacing.extraSmall
                            implicitHeight: completedHeaderRow.implicitHeight + Tokens.padding.small * 2
                            radius: Tokens.rounding.medium
                            color: Colours.tPalette.m3surfaceContainer

                            StateLayer {
                                color: Colours.palette.m3onSurface
                                onClicked: root.showCompleted = !root.showCompleted
                            }

                            RowLayout {
                                id: completedHeaderRow

                                anchors.fill: parent
                                anchors.leftMargin: Tokens.padding.medium
                                anchors.rightMargin: Tokens.padding.medium
                                anchors.topMargin: Tokens.padding.small
                                anchors.bottomMargin: Tokens.padding.small
                                spacing: Tokens.spacing.small

                                MaterialIcon {
                                    text: "done_all"
                                    fontStyle: Tokens.font.icon.medium
                                    color: Colours.palette.m3onSurfaceVariant
                                }

                                StyledText {
                                    Layout.fillWidth: true
                                    text: Tr.tr("Completed (%1)").arg(root.completedTasks.length)
                                    font: Tokens.font.body.builders.medium.weight(Font.DemiBold).build()
                                    color: Colours.palette.m3onSurfaceVariant
                                }

                                MaterialIcon {
                                    text: root.showCompleted ? "expand_less" : "expand_more"
                                    fontStyle: Tokens.font.icon.medium
                                    color: Colours.palette.m3onSurfaceVariant
                                }
                            }
                        }

                        // Completed Tasks List (when expanded)
                        Repeater {
                            model: ScriptModel {
                                values: root.showCompleted ? root.completedTasks : []
                            }

                            delegate: TaskRowCard {
                                isCompletedRow: true
                            }
                        }
                    }
                }

                StyledScrollBar {
                    id: tasksScrollBar

                    anchors.top: parent.top
                    anchors.bottom: parent.bottom
                    anchors.right: parent.right
                    flickable: tasksFlickable
                }
            }
        }
    }

    component TaskRowCard: StyledRect {
        id: taskCard

        required property int index
        required property var modelData
        property bool isCompletedRow: false

        readonly property bool isUrgent: !isCompletedRow && modelData.isUrgentRed && !modelData.isPerformanceSession
        readonly property bool isPerf: !isCompletedRow && modelData.isPerformanceSession

        Layout.fillWidth: true
        implicitHeight: taskRowLayout.implicitHeight + Tokens.padding.medium * 2
        radius: Tokens.rounding.large
        opacity: isCompletedRow ? 0.72 : 1.0
        color: {
            if (isUrgent)
                return Colours.palette.m3errorContainer;
            if (isPerf)
                return Colours.palette.m3tertiaryContainer;
            return Colours.tPalette.m3surfaceContainer;
        }

        RowLayout {
            id: taskRowLayout

            anchors.fill: parent
            anchors.margins: Tokens.padding.medium
            spacing: Tokens.spacing.medium

            // Interactive Checkbox
            StyledRect {
                id: checkboxBox

                Layout.alignment: Qt.AlignVCenter
                implicitWidth: 28
                implicitHeight: 28
                radius: Tokens.rounding.small
                color: taskCard.isCompletedRow ? Colours.palette.m3primary : "transparent"
                border.width: taskCard.isCompletedRow ? 0 : 2
                border.color: {
                    if (taskCard.isUrgent)
                        return Colours.palette.m3onErrorContainer;
                    if (taskCard.isPerf)
                        return Colours.palette.m3onTertiaryContainer;
                    return Colours.palette.m3outline;
                }

                StateLayer {
                    radius: Tokens.rounding.small
                    color: taskCard.isCompletedRow ? Colours.palette.m3onPrimary : Colours.palette.m3primary
                    onClicked: root.toggleTaskCompleted(taskCard.modelData, !taskCard.isCompletedRow)
                }

                MaterialIcon {
                    visible: taskCard.isCompletedRow
                    anchors.centerIn: parent
                    text: "check"
                    fontStyle: Tokens.font.icon.medium
                    color: Colours.palette.m3onPrimary
                    fill: 1
                }
            }

            // Subject Icon Badge
            StyledRect {
                Layout.alignment: Qt.AlignVCenter
                implicitWidth: 40
                implicitHeight: 40
                radius: Tokens.rounding.medium
                color: {
                    if (taskCard.isUrgent)
                        return Colours.palette.m3error;
                    if (taskCard.isPerf)
                        return Colours.palette.m3tertiary;
                    return Colours.palette.m3secondaryContainer;
                }

                MaterialIcon {
                    anchors.centerIn: parent
                    text: taskCard.modelData.subjectIcon
                    fontStyle: Tokens.font.icon.medium
                    color: {
                        if (taskCard.isUrgent)
                            return Colours.palette.m3onError;
                        if (taskCard.isPerf)
                            return Colours.palette.m3onTertiary;
                        return Colours.palette.m3onSecondaryContainer;
                    }
                    fill: 1
                }
            }

            // Task Title & Subject Name
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2

                StyledText {
                    Layout.fillWidth: true
                    text: taskCard.modelData.title
                    font: {
                        const f = Qt.font(Tokens.font.body.builders.medium.weight(Font.DemiBold).build());
                        f.strikeout = taskCard.isCompletedRow;
                        return f;
                    }
                    color: {
                        if (taskCard.isUrgent)
                            return Colours.palette.m3onErrorContainer;
                        if (taskCard.isPerf)
                            return Colours.palette.m3onTertiaryContainer;
                        return Colours.palette.m3onSurface;
                    }
                    elide: Text.ElideRight
                    maximumLineCount: 2
                    wrapMode: Text.Wrap
                }

                StyledText {
                    Layout.fillWidth: true
                    text: taskCard.modelData.subjectName
                    font: Tokens.font.body.small
                    color: {
                        if (taskCard.isUrgent)
                            return Colours.palette.m3onErrorContainer;
                        if (taskCard.isPerf)
                            return Colours.palette.m3onTertiaryContainer;
                        return Colours.palette.m3onSurfaceVariant;
                    }
                    opacity: 0.88
                    elide: Text.ElideRight
                    maximumLineCount: 1
                }
            }

            // Deadline Pill Badge
            StyledRect {
                Layout.alignment: Qt.AlignVCenter
                implicitWidth: deadlineBadgeText.implicitWidth + Tokens.padding.small * 2
                implicitHeight: deadlineBadgeText.implicitHeight + 8
                radius: Tokens.rounding.full
                color: {
                    if (taskCard.isUrgent)
                        return Colours.palette.m3error;
                    if (taskCard.isPerf)
                        return Colours.palette.m3tertiary;
                    return Colours.palette.m3surfaceContainerHighest;
                }

                StyledText {
                    id: deadlineBadgeText

                    anchors.centerIn: parent
                    text: taskCard.modelData.deadlineLabel
                    font: Tokens.font.body.builders.small.weight(Font.Bold).build()
                    color: {
                        if (taskCard.isUrgent)
                            return Colours.palette.m3onError;
                        if (taskCard.isPerf)
                            return Colours.palette.m3onTertiary;
                        return Colours.palette.m3onSurfaceVariant;
                    }
                }
            }
        }
    }
}
