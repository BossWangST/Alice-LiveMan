/*
 * <Alice LiveMan>
 * Copyright (C) <2018>  <NekoSunflower>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package site.alice.liveman.service.broadcast;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import site.alice.liveman.event.MediaProxyEvent;
import site.alice.liveman.event.MediaProxyEventListener;
import site.alice.liveman.mediaproxy.MediaProxyManager;
import site.alice.liveman.mediaproxy.proxytask.MediaProxyTask;
import site.alice.liveman.model.AccountInfo;
import site.alice.liveman.model.ChannelInfo;
import site.alice.liveman.model.LiveManSetting;
import site.alice.liveman.model.VideoInfo;
import site.alice.liveman.utils.BilibiliApiUtil;
import site.alice.liveman.utils.FfmpegUtil;
import site.alice.liveman.utils.ProcessUtil;
import site.alice.liveman.web.dataobject.ActionResult;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class BroadcastServiceManager implements ApplicationContextAware {
    private static final ThreadPoolExecutor            threadPoolExecutor = new ThreadPoolExecutor(50, 50, 100000, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(10));
    private              Map<String, BroadcastService> broadcastServiceMap;
    @Autowired
    private              LiveManSetting                liveManSetting;
    @Autowired
    private              BilibiliApiUtil               bilibiliApiUtil;

    @PostConstruct
    public void init() {
        MediaProxyManager.addListener(new MediaProxyEventListener() {
            @Override
            public void onProxyStart(MediaProxyEvent e) {
                VideoInfo videoInfo = e.getMediaProxyTask().getVideoInfo();
                if (videoInfo != null) {
                    BroadcastTask broadcastTask;
                    if (videoInfo.getBroadcastTask() == null) {
                        broadcastTask = new BroadcastTask(videoInfo);
                        if (!videoInfo.setBroadcastTask(broadcastTask)) {
                            BroadcastTask currentBroadcastTask = videoInfo.getBroadcastTask();
                            try {
                                log.warn("试图创建推流任务的媒体资源已存在推流任务[roomId={}]，这是不正常的意外情况，将尝试终止已存在的推流任务[videoId={}]", currentBroadcastTask.getBroadcastAccount().getRoomId(), videoInfo.getVideoId());
                                if (!currentBroadcastTask.terminateTask()) {
                                    log.warn("终止转播任务失败：CAS操作失败");
                                }
                            } catch (Throwable throwable) {
                                log.error("启动推流任务时发生异常", throwable);
                            }
                        }
                    } else {
                        broadcastTask = videoInfo.getBroadcastTask();
                    }
                    threadPoolExecutor.execute(broadcastTask);
                }
            }

            @Override
            public void onProxyStop(MediaProxyEvent e) {
                VideoInfo videoInfo = e.getMediaProxyTask().getVideoInfo();
                BroadcastTask broadcastTask = videoInfo.getBroadcastTask();
                if (broadcastTask != null) {
                    AccountInfo broadcastAccount = broadcastTask.getBroadcastAccount();
                    if (broadcastAccount != null) {
                        broadcastAccount.removeCurrentVideo(videoInfo);
                    }
                }
            }
        });
    }

    public BroadcastTask createSingleBroadcastTask(VideoInfo videoInfo, AccountInfo broadcastAccount) throws Exception {
        if (broadcastAccount.setCurrentVideo(videoInfo)) {
            BroadcastTask broadcastTask = new BroadcastTask(videoInfo, broadcastAccount);
            if (!videoInfo.setBroadcastTask(broadcastTask)) {
                throw new RuntimeException("提供的VideoInfo已包含推流任务，请先终止现有任务[videoId=" + videoInfo.getVideoId() + "]");
            }
            Map<String, MediaProxyTask> executedProxyTaskMap = MediaProxyManager.getExecutedProxyTaskMap();
            // 如果要推流的媒体已存在，则直接创建推流任务
            MediaProxyTask mediaProxyTask = executedProxyTaskMap.get(videoInfo.getVideoId());
            if (mediaProxyTask != null) {
                if (mediaProxyTask.getVideoInfo() != null && mediaProxyTask.getVideoInfo().getBroadcastTask() != null) {
                    throw new RuntimeException("此媒体已在推流任务列表中，无法添加");
                }
                threadPoolExecutor.execute(broadcastTask);
            } else {
                // 创建直播流代理任务
                mediaProxyTask = MediaProxyManager.createProxy(videoInfo);
                if (mediaProxyTask == null) {
                    throw new RuntimeException("MediaProxyTask创建失败");
                }
            }
            return broadcastTask;
        } else {
            throw new RuntimeException("无法创建转播任务，直播间已被节目[" + broadcastAccount.getCurrentVideo().getTitle() + "]占用！");
        }
    }

    public AccountInfo getBroadcastAccount(VideoInfo videoInfo) {
        ChannelInfo channelInfo = videoInfo.getChannelInfo();
        String defaultAccountId = channelInfo.getDefaultAccountId();
        if (defaultAccountId != null) {
            AccountInfo accountInfo = liveManSetting.findByAccountId(defaultAccountId);
            if (accountInfo != null && !accountInfo.isDisable() && accountInfo.setCurrentVideo(videoInfo)) {
                return accountInfo;
            }
        }
        if (channelInfo.isAutoBalance()) {
            /* 默认直播间不可用或没有设置默认 */
            Set<AccountInfo> accounts = liveManSetting.getAccounts();
            for (AccountInfo accountInfo : accounts) {
                if (accountInfo.isJoinAutoBalance() && !accountInfo.isDisable() && accountInfo.setCurrentVideo(videoInfo)) {
                    return accountInfo;
                }
            }
        }
        throw new RuntimeException("频道[" + channelInfo.getChannelName() + "], videoId=" + videoInfo.getVideoId() + "没有找到可以推流的直播间");
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        broadcastServiceMap = applicationContext.getBeansOfType(BroadcastService.class);
    }

    public BroadcastService getBroadcastService(String accountSite) {
        for (BroadcastService broadcastService : broadcastServiceMap.values()) {
            if (broadcastService.isMatch(accountSite)) {
                return broadcastService;
            }
        }
        throw new BeanDefinitionStoreException("没有找到可以推流到[" + accountSite + "]的BroadcastService");
    }

    public class BroadcastTask implements Runnable {

        private VideoInfo   videoInfo;
        private long        pid;
        private AccountInfo broadcastAccount;
        private boolean     terminate;
        private boolean     singleTask;

        public BroadcastTask(VideoInfo videoInfo, AccountInfo broadcastAccount) {
            this.videoInfo = videoInfo;
            this.broadcastAccount = broadcastAccount;
            singleTask = true;
        }

        public BroadcastTask(VideoInfo videoInfo) {
            this.videoInfo = videoInfo;
        }

        public VideoInfo getVideoInfo() {
            return videoInfo;
        }

        public long getPid() {
            return pid;
        }

        public AccountInfo getBroadcastAccount() {
            return broadcastAccount;
        }

        public boolean isTerminate() {
            return terminate;
        }

        public boolean isSingleTask() {
            return singleTask;
        }

        @Override
        public void run() {
            while (MediaProxyManager.getExecutedProxyTaskMap().containsKey(videoInfo.getVideoId()) && !terminate) {
                try {
                    if (!singleTask) {
                        broadcastAccount = BroadcastServiceManager.this.getBroadcastAccount(videoInfo);
                        bilibiliApiUtil.postDynamic(broadcastAccount);
                    }
                    while (broadcastAccount.getCurrentVideo() == videoInfo) {
                        try {
                            VideoInfo currentVideo = broadcastAccount.getCurrentVideo();
                            String broadcastAddress = getBroadcastService(broadcastAccount.getAccountSite()).getBroadcastAddress(broadcastAccount);
                            String ffmpegCmdLine = FfmpegUtil.buildFfmpegCmdLine(currentVideo, broadcastAddress);
                            pid = ProcessUtil.createProcess(liveManSetting.getFfmpegPath(), ffmpegCmdLine, false);
                            log.info("[" + broadcastAccount.getRoomId() + "@" + broadcastAccount.getAccountSite() + ", videoId=" + currentVideo.getVideoId() + "]推流进程已启动[PID:" + pid + "][" + ffmpegCmdLine.replace("\t", " ") + "]");
                            // 等待进程退出或者任务结束
                            while (broadcastAccount.getCurrentVideo() != null && !ProcessUtil.waitProcess(pid, 1000)) ;
                            // 杀死进程
                            ProcessUtil.killProcess(pid);
                            log.info("[" + broadcastAccount.getRoomId() + "@" + broadcastAccount.getAccountSite() + ", videoId=" + currentVideo.getVideoId() + "]推流进程已终止PID:" + pid);
                        } catch (Throwable e) {
                            log.error("startBroadcast failed", e);
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException ignore) {
                            }
                        }
                    }
                } catch (Throwable e) {
                    log.error("startBroadcast failed", e);
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignore) {
                    }
                }
            }
        }

        public boolean terminateTask() {
            log.info("强制终止节目[" + videoInfo.getTitle() + "][videoId=" + videoInfo.getVideoId() + "]的推流任务[roomId=" + broadcastAccount.getRoomId() + "]");
            if (broadcastAccount.removeCurrentVideo(videoInfo)) {
                terminate = true;
                videoInfo.removeBroadcastTask(this);
                ProcessUtil.killProcess(pid);
                return true;
            } else {
                return false;
            }
        }
    }

}
