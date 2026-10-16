package io.dataease.system.dao.ext.mapper;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.annotations.*;

@Mapper
public interface EmbedKeyMapper {
    @Getter
    @Setter
    class Entry {
        private String kid;
        private String tenant;
        private String ciphertext;
        private boolean enabled;
    }

    @Select("SELECT kid, tenant, ciphertext, enabled FROM de_embed_key ORDER BY tenant, kid")
    List<Entry> list();

    @Select("SELECT kid, tenant, ciphertext, enabled FROM de_embed_key WHERE kid = #{kid}")
    Entry find(String kid);

    @Insert("INSERT INTO de_embed_key (kid, tenant, ciphertext, enabled) VALUES (#{kid}, #{tenant}, #{ciphertext}, #{enabled})")
    int insert(Entry entry);

    @Update("UPDATE de_embed_key SET ciphertext = #{ciphertext}, enabled = #{enabled} WHERE kid = #{kid} AND tenant = #{tenant}")
    int update(Entry entry);
}
