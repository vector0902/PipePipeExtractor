package org.schabi.newpipe.extractor.services.bilibili.extractors;

import static org.schabi.newpipe.extractor.services.bilibili.BilibiliService.getHeaders;
import static org.schabi.newpipe.extractor.services.bilibili.utils.getWbiResult;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;

import javax.annotation.Nonnull;

public class BilibiliFeedExtractor extends KioskExtractor<StreamInfoItem> {
    private static final String RECOMMEND_BASE_URL = "https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd";
    private static final int PAGE_SIZE = 30;

    public BilibiliFeedExtractor(StreamingService streamingService, ListLinkHandler linkHandler, String kioskId) {
        super(streamingService, linkHandler, kioskId);
    }

    private JsonObject response = new JsonObject();
    private Document document;

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        return getId();
    }

    @Nonnull
    @Override
    public InfoItemsPage<StreamInfoItem> getInitialPage() throws IOException, ExtractionException {
        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        JsonArray results;
        switch (getId()) {
            case "Recommended Videos":
                results = response.getObject("data").getArray("item");
                for (int i = 0; i < results.size(); i++) {
                    collector.commit(new BilibiliRecommendedVideosInfoItemExtractor(results.getObject(i)));
                }
                break;
            case "Recommended Lives":
                results = response.getObject("data").getArray("list");
                for (int i = 0; i < results.size(); i++) {
                    collector.commit(new BilibiliRecommendLiveInfoItemExtractor(results.getObject(i)));
                }
                break;
            case "Top 100":
                results = response.getObject("data").getArray("list");
                for (int i = 0; i < results.size(); i++) {
                    collector.commit(new BilibiliTrendingInfoItemExtractor(results.getObject(i)));
                }
                break;
        }
        if (ServiceList.BiliBili.getFilterTypes().contains("recommendations")) {
            collector.applyBlocking(ServiceList.BiliBili.getFilterConfig());
        }

        if ("Recommended Videos".equals(getId())) {
            Page nextPage = buildNextPageUrl(2);
            return new InfoItemsPage<>(collector, nextPage);
        } else {
            return new InfoItemsPage<>(collector, null);
        }
    }

    @Override
    public InfoItemsPage<StreamInfoItem> getPage(Page page) throws IOException, ExtractionException {
        if (!"Recommended Videos".equals(getId())) {
            return null;
        }

        final StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());

        try {
            String url = page.getUrl();
            response = JsonParser.object().from(getDownloader().get(url, getHeaders(getOriginalUrl())).responseBody());
        } catch (JsonParserException e) {
            e.printStackTrace();
            return new InfoItemsPage<>(collector, null);
        }

        JsonArray results = response.getObject("data").getArray("item");
        for (int i = 0; i < results.size(); i++) {
            collector.commit(new BilibiliRecommendedVideosInfoItemExtractor(results.getObject(i)));
        }

        if (ServiceList.BiliBili.getFilterTypes().contains("recommendations")) {
            collector.applyBlocking(ServiceList.BiliBili.getFilterConfig());
        }

        if (results.size() > 0) {
            int currentPage = extractFreshIdx(page.getUrl());
            Page nextPage = buildNextPageUrl(currentPage + 1);
            return new InfoItemsPage<>(collector, nextPage);
        } else {
            return new InfoItemsPage<>(collector, null);
        }
    }

    @Override
    public void onFetchPage(Downloader downloader) throws IOException, ExtractionException {
        switch (getId()) {
            case "Recommended Videos":
            default:
                try {
                    LinkedHashMap<String, String> params = new LinkedHashMap<>();
                    params.put("fresh_type", "4");
                    params.put("ps", String.valueOf(PAGE_SIZE));
                    params.put("fresh_idx", "1");

                    String signedUrl = getWbiResult(RECOMMEND_BASE_URL, params);

                    response = JsonParser.object().from(getDownloader().get(signedUrl, getHeaders(getOriginalUrl())).responseBody());
                } catch (JsonParserException e) {
                    e.printStackTrace();
                } catch (Exception e) {
                    try {
                        response = JsonParser.object().from(getDownloader().get("https://api.bilibili.com/x/web-interface/index/top/rcmd?fresh_type=3", getHeaders(getOriginalUrl())).responseBody());
                    } catch (JsonParserException ex) {
                        ex.printStackTrace();
                    }
                }
                break;
            case "Top 100":
                try {
                    response = JsonParser.object().from(downloader.get(getUrl(), getHeaders(getOriginalUrl())).responseBody());
                } catch (JsonParserException e) {
                    throw new RuntimeException(e);
                }
                break;
            case "Recommended Lives":
                try {
                    response = JsonParser.object().from(downloader.get(getUrl() + "&page=1", getHeaders(getOriginalUrl())).responseBody());
                } catch (JsonParserException e) {
                    throw new RuntimeException(e);
                }
                break;
        }
    }

    private Page buildNextPageUrl(int freshIdx) {
        try {
            LinkedHashMap<String, String> params = new LinkedHashMap<>();
            params.put("fresh_type", "4");
            params.put("ps", String.valueOf(PAGE_SIZE));
            params.put("fresh_idx", String.valueOf(freshIdx));

            String nextUrl = getWbiResult(RECOMMEND_BASE_URL, params);
            return new Page(nextUrl, Collections.<String, String>emptyMap());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private int extractFreshIdx(String url) {
        Pattern pattern = Pattern.compile("fresh_idx=(\\d+)");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return 1;
            }
        }
        return 1;
    }

}
