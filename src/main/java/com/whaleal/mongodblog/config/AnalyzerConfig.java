package com.whaleal.mongodblog.config;

import com.whaleal.mongodblog.parser.CompositeLogParser;
import com.whaleal.mongodblog.parser.LegacyLogParser;
import com.whaleal.mongodblog.parser.LogParser;
import com.whaleal.mongodblog.parser.QueryPatternNormalizer;
import com.whaleal.mongodblog.parser.StructuredLogParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnalyzerConfig {
    @Bean
    public LogParser logParser() {
        QueryPatternNormalizer normalizer = new QueryPatternNormalizer();
        return new CompositeLogParser(
                new StructuredLogParser(normalizer),
                new LegacyLogParser(normalizer)
        );
    }
}

