package io.dataease.menu.manage;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.dataease.api.menu.vo.MenuMeta;
import io.dataease.api.menu.vo.MenuVO;
import io.dataease.i18n.Translator;
import io.dataease.license.config.XpackInteract;
import io.dataease.menu.bo.MenuTreeNode;
import io.dataease.menu.dao.auto.entity.CoreMenu;
import io.dataease.menu.dao.auto.mapper.CoreMenuMapper;
import io.dataease.utils.BeanUtils;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class MenuManage {

    private static final String I18N_PREFIX = "i18n_menu.";

    private final static int ROOTID = 0;

    @Resource
    private CoreMenuMapper coreMenuMapper;

    @XpackInteract(value = "menuApi")
    public List<MenuVO> query(List<CoreMenu> coreMenus) {
        List<MenuTreeNode> menuTreeNodes = new ArrayList<>(coreMenus.stream().map(menu -> BeanUtils.copyBean(new MenuTreeNode(), menu)).toList());
        menuTreeNodes.sort(Comparator.comparing(MenuTreeNode::getMenuSort));
        List<MenuTreeNode> treeNodes = buildPOTree(menuTreeNodes);
        return convertTree(treeNodes);
    }

    public List<CoreMenu> coreMenus() {
        QueryWrapper<CoreMenu> wrapper = new QueryWrapper<>();
        wrapper.orderByAsc("menu_sort");
        return coreMenuMapper.selectList(wrapper);
    }

    /** Local IAM does not invoke the enterprise menu replacement. */
    public List<MenuVO> localMenus() {
        var user = io.dataease.localaccess.security.LocalAccessContext.require();
        List<CoreMenu> menus = coreMenus().stream().filter(menu -> {
            long id = menu.getId();
            if (user.admin()) return !isXpackMenu(menu);
            if (id == 15 || id == 16) return user.admin();
            if (user.tenantId() == null) return false;
            if (id == 2 || id == 3) return user.permits("dashboard");
            if (id == 4) return user.permits("dataset");
            if (id == 5 || id == 11) return user.permits("dataset");
            if (id == 6 || id == 12) return false;
            return false;
        }).toList();
        List<MenuTreeNode> nodes = new ArrayList<>(menus.stream().map(menu -> BeanUtils.copyBean(new MenuTreeNode(), menu)).toList());
        List<MenuVO> result = convertTree(buildPOTree(nodes));
        MenuVO password = new MenuVO(); password.setPath("/modify-pwd"); password.setName("modify-pwd");
        password.setComponent("system/modify-pwd"); password.setInLayout(true); password.setHidden(true); password.setMeta(new MenuMeta());
        result.add(password);
        if (user.admin()) {
            MenuVO local = new MenuVO();
            local.setPath("/local-access"); local.setName("local-access"); local.setComponent("system/local-access");
            local.setInLayout(true); local.setPlugin(false);
            MenuMeta meta = new MenuMeta(); meta.setTitle(Translator.get("i18n_menu.local_access")); local.setMeta(meta);
            result.add(0, local);
        }
        return result;
    }

    private List<MenuTreeNode> buildPOTree(List<MenuTreeNode> coreMenus) {
        List<MenuTreeNode> result = new ArrayList<>();
        Map<Long, List<MenuTreeNode>> childMap = coreMenus.stream().collect(Collectors.groupingBy(CoreMenu::getPid));
        coreMenus.forEach(po -> {
            po.setChildren(childMap.get(po.getId()));
            if (po.getPid() == ROOTID) {
                result.add(po);
            }
        });
        return result;
    }

    private List<MenuVO> convertTree(List<MenuTreeNode> roots) {
        List<MenuVO> result = new ArrayList<>();
        for (MenuTreeNode menuTreeNode : roots) {
            MenuVO vo = convert(menuTreeNode);
            List<MenuTreeNode> children = null;
            if (CollectionUtils.isNotEmpty(children = menuTreeNode.getChildren())) {
                vo.setChildren(convertTree(children));
            }
            if (CollectionUtils.isNotEmpty(vo.getChildren()) || menuTreeNode.getType() != 1) {
                result.add(vo);
            }
        }
        return result;
    }

    private MenuVO convert(CoreMenu coreMenu) {

        if (ROOTID != coreMenu.getPid() && StringUtils.startsWith(coreMenu.getPath(), "/")) {
            coreMenu.setPath(coreMenu.getPath().substring(1));
        }
        MenuVO menuVO = new MenuVO();
        BeanUtils.copyBean(menuVO, coreMenu, "children");
        MenuMeta meta = new MenuMeta();
        meta.setTitle(Translator.get(I18N_PREFIX + coreMenu.getName()));
        meta.setIcon(coreMenu.getIcon());
        menuVO.setMeta(meta);

        menuVO.setPlugin(isXpackMenu(coreMenu));
        return menuVO;
    }

    private boolean isXpackMenu(CoreMenu coreMenu) {
        if (coreMenu.getId().equals(21L)) return false;
        return coreMenu.getId().equals(7L)
                || coreMenu.getPid().equals(7L)
                || coreMenu.getId().equals(14L)
                || coreMenu.getId().equals(17L)
                || coreMenu.getId().equals(18L)
                || coreMenu.getId().equals(29L)
                || coreMenu.getPid().equals(21L)
                || coreMenu.getId().equals(25L)
                || coreMenu.getId().equals(26L)
                || coreMenu.getId().equals(27L)
                || coreMenu.getId().equals(28L)
                || coreMenu.getId().equals(35L)
                || coreMenu.getId().equals(40L)
                || coreMenu.getId().equals(50L)
                || coreMenu.getId().equals(60L)
                || coreMenu.getId().equals(61L)
                || coreMenu.getId().equals(65L)
                || coreMenu.getId().equals(80L)
                || coreMenu.getId().equals(90L)
                || coreMenu.getPid().equals(70L);
    }
}
