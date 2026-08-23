//@charset 'gbk';
var tjid = $('#tjid').attr('value');
//独家选股
function openDiv($dom) {
    if (tjid == 'yjkb') {
        TA.log({ 'id': 'db_55e411a6_323' });
    } else if (tjid == 'yjyg') {
        TA.log({ 'id': 'db_55ebdaf8_6' });
    } else if (tjid == 'yjgg') {
        TA.log({ 'id': 'db_55efe48c_985' });
    } else if (tjid == 'yypl') {
        TA.log({ 'id': 'db_55f29c3f_227' });
    } else if (tjid == 'sgpx') {
        TA.log({ 'id': 'db_55f13d06_111' });
    }
    $dom.show();
}

function closeDiv($dom) {
    $dom.hide();
}

$(function() {
    // 判断是否登录
    function isLogined() {
        var uname = unescape(unescape(THS.cookie.get('escapename')));
        if (uname.length > 0) {
            return true;
        } else {
            return false;
        }
    }
    // 隐藏AH股
    function hideAH() {
        if (isLogined() === false) {
            $('.js-login').hide()
        } else {
            $('.js-login').show()
        }
    }
    //菜单栏
    var curidx = $('.inner-nav a').index($('.inner-nav a.cur'));
    var $subNav = $('.sub-nav');
    $subNav.find('span').each(function() {
        $(this).attr('blockwidth', $(this).outerWidth())
    });
    $subNav.hide();
    $('.inner-nav a').mouseenter(function() {
        $(this).addClass('cur').siblings().removeClass('cur');
        var navName = $(this).attr('nav') + '-list';
        var $navList = $subNav.find('.' + navName);
        hideAH();
        if ($navList.length > 0) {
            var top = $(this).offset().top + $(this).outerHeight();
            var left = $(this).offset().left;
            // var maxLeft = $('.container').offset().left + 1200;
            var maxLeft = $('.inner-nav a:last').offset().left + $('.inner-nav a:last').outerWidth();
            var width = parseFloat($navList.attr('blockwidth'));
            if (width + left > maxLeft) {
                left = maxLeft - width;
                if ($('.inner-nav').find('a.cur').attr('nav') === 'more') {
                    if (isLogined() === false) {
                        var tempWidth = 74.69;
                        left += tempWidth;

                    }
                }
            }
            $subNav.css({
                'top': top,
                'left': left,
                'zIndex': 10
            }).show();
            $navList.show().siblings().hide();
        } else {
            $subNav.hide();
        }
    })
    $('.nav').mouseleave(function() {
        $('.inner-nav a').eq(curidx).addClass('cur').siblings().removeClass('cur');
        $subNav.hide();
    });

    //表格选中列突出显示
    function setTableCss() {
        var colidx = $('.m-table th.cur').attr('colidx');
        var trLen = $('.m-table tbody tr').length;
        $('.m-table tbody tr').each(function(i) {
            if (i == 0) {
                $(this).find('td').eq(colidx).addClass('selfirst');
            }
            if (i == trLen - 1) {
                $(this).find('td').eq(colidx).addClass('sellast');
            }
            $(this).find('td').eq(colidx).addClass('selected');
        });
    }
    setTableCss();

    //表格高亮
    $('.m-table td').live('mouseenter', function() {
        var index = $(this).index();
        var $tr = $('.m-table tr');
        if (location.href.indexOf('yjyg') > 0 || location.href.indexOf('mrtbts') > 0 || location.href.indexOf('tfpts') > 0 || location.href.indexOf('gddh') > 0 || location.href.indexOf('tbcl') > 0) {
            $(this).parent('tr').addClass('highlight');
        } else {
            $tr.each(function() {
                $(this).find('td').removeClass('highlight').eq(index).addClass('highlight');
            })
            $tr.removeClass('highlight');
            $(this).parent('tr').addClass('highlight');
        }

    }).live('mouseleave', function() {
        $('.m-table tr,.m-table td').removeClass('highlight');
    });



    //右侧固定栏,反馈
    var txtcont = '如果您相对我们的产品提出意见或建议，请在这里填写，您的建议是同花顺数据进步的动力!';
    var telcont = '留下邮箱/QQ号/手机号以便我们回复您。';
    $('.fixedbar .feedback').mouseenter(function() {
        $('.feedback-box').show();
    });
    $('.feedback-box .txt').focus(function() {
        if ($(this).val() == txtcont) {
            $(this).val('');
        }
    }).blur(function() {
        if ($(this).val() == '') {
            $(this).val(txtcont);
        }
    });
    $('.feedback-box .tel').focus(function() {
        if ($(this).val() == telcont) {
            $(this).val('');
        }
    }).blur(function() {
        if ($(this).val() == '') {
            $(this).val(telcont);
        }
    });
    $('.feedback-box .btn').click(function() {
        if (tjid == 'yjkb') {
            TA.log({ 'id': 'db_55e41132_794' });
        } else if (tjid == 'yjyg') {

        } else if (tjid == 'yjgg') {

        } else if (tjid == 'yypl') {

        } else if (tjid == 'sgpx') {

        }
        var txt = $.trim($('.feedback-box .txt').val());
        var tel = $.trim($('.feedback-box .tel').val());
        if (txt != '' && txt != txtcont && tel != '' && tel != telcont) {
            //提交
            var c_id = $('#cate_id').val();
            var u_name = $('#username').val();
            $.ajax({
                url: '/vote/index.php?s=/Index/post',
                type: 'GET',
                data: {
                    content: txt,
                    contact: tel,
                    cate_id: c_id,
                    username: u_name,
                    code: '',
                    referer: document.location.href
                },
                dataType: 'jsonp',
                callback: '_Callback',
                error: function() {
                    alert('提交失败');
                },
                success: function(json) {
                    if (json.status == 1) {
                        alert("提交成功");
                    } else {
                        alert(json.info);
                    }
                }
            });
            $('.feedback-box').hide();
            $('.feedback-box .txt').html(txtcont).val(txtcont);
            $('.feedback-box .tel').val(telcont);
        } else {
            alert('请填写完整信息！');
        }
    });
    $(document).click(function(e) {
        var target = $(e.target);
        if (target.closest('.feedback').length < 1) {
            $('.feedback-box').hide();
            $('.feedback-box .txt').html(txtcont).val(txtcont);
            $('.feedback-box .tel').val(telcont);
        }
    });
    //收藏
    $('.fixedbar .collect').click(function() {
        if (tjid == 'yjkb') {
            TA.log({ 'id': 'db_55e41179_126' });
        } else if (tjid == 'yjyg') {
            TA.log({ 'id': 'db_55ebdad0_258' });
        } else if (tjid == 'yjgg') {
            TA.log({ 'id': 'db_55efe548_149' });
        } else if (tjid == 'yypl') {
            TA.log({ 'id': 'db_55f29c14_732' });
        } else if (tjid == 'sgpx') {
            TA.log({ 'id': 'db_55f13d3f_924' });
        }
        addFavorite();
    });

    function addFavorite() {
        try {
            window.external.addFavorite(window.location.href, window.document.title);
        } catch (e) {
            try {
                window.sidebar.addPanel(window.document.title, window.location, "");
            } catch (e) {
                alert("加入收藏失败，请使用Ctrl+D进行添加");
            }
        }
    }

    //URL参数处理
    var diffRequest = function(param) {
        var request = eval('(' + $('#request').val() + ')');
        var requestQuery = $('#requestQuery').val() ? '?' + $('#requestQuery').val() : '';
        var params = [];
        for (var i in param) {
            request[i] = param[i];
        }
        for (i in request) {
            if (request[i] && (typeof(param.page) != 'undefined' || i != 'page')) {
                params.push(i + '/' + request[i]);
            }
        }
        return params.join('/') + '/' + requestQuery;
    };


    //页面内容变换
    var changeContent = function(param, targetid, callback) {
        var baseUrl = $('#baseUrl').val();
        var url = '/' + baseUrl + '/' + param;
        if (!targetid) {
            targetid = 'J-ajax-main';
        }
        var $loading = $('#' + targetid).prev('.page-loading');
        $loading.ajaxStart(function() {
            var top = $('#' + targetid).position().top;
            var height = $('#' + targetid).outerHeight();
            $('.page-loading .mask,.page-loading .loading-img').css('height', '100%');
            $(this).css({
                'top': top,
                'height': height
            }).show();
        });
        $.ajax({
            url: url,
            type: 'get',
            dataType: 'html',
            success: function(data) {
                $('#' + targetid).html(data);
                $('.page-loading').hide();
                setTableCss();
                tableThead();
            }
        });
    };

    function getReport() {
        var report = $('#report').attr('date');
        return report;
    }

    function getBoard() {
        var board = '';
        $('.J-ajax-board .J-board-item').each(function() {
            if ($(this).hasClass('cur')) {
                board = $(this).attr('board');
                return false;
            }
        });
        return board;
    }

    function getField() {
        var field = '';
        $('.J-ajax-table .J-ajax-a').each(function() {
            if ($(this).parent('th').hasClass('cur')) {
                field = $(this).attr('field');
                return false;
            }
        });
        return field;
    }

    function getOrder() {
        var order = '';
        $('.J-ajax-table .J-ajax-a').each(function() {
            if ($(this).parent('th').hasClass('cur')) {
                order = $(this).attr('order') ? $(this).attr('order') : 'DESC';
                return false;
            }
        });
        return order;
    }

    function getPage($page) {
        var page = '';
        var $a = $page.find('a');
        $a.each(function() {
            if ($(this).hasClass('click-cur')) {
                page = $(this).attr('page');
                return false;
            }
        });
        return page;
    }
    //tab页切换
    $('.J-ajax-board .J-board-item').live('click', function() {
        $(this).addClass('cur').siblings().removeClass('cur');
        var report = getReport();
        var board = getBoard();
        var field = getField();
        $('#board').attr('value', board);
        $('#field').attr('value', field);
        if (location.href.indexOf('mrtbts') > 0 || location.href.indexOf('tfpts') > 0) {
            var param = diffRequest({
                'board': board,
                'ajax': 1
            });
        } else {
            var param = diffRequest({
                'date': report,
                'board': board,
                'field': field,
                'ajax': 1
            });
        }
        changeContent(param);
        if (tjid == 'yjkb') {
            TA.log({ 'id': 'db_55e41085_305' });
        } else if (tjid == 'yjyg') {
            TA.log({ 'id': 'db_55ebdaaf_234' });
        } else if (tjid == 'yjgg') {
            TA.log({ 'id': 'db_55efe4c8_768' });
        } else if (tjid == 'yypl') {
            TA.log({ 'id': 'db_55f29bc3_447' });
        } else if (tjid == 'sgpx') {
            TA.log({ 'id': 'db_55f13cdd_634' });
        }
    });
    //表格排序
    $('.J-ajax-table .J-ajax-a').live('click', function() {
        if ($(this).parent('th').hasClass('cur')) {
            $(this).parent('th').find('.arr-down').toggleClass('arr-up');
            if (!$(this).attr('order')) {
                $(this).attr('order', 'ASC');
            }
        } else {
            $('.J-ajax-table th').removeClass('cur');
            $(this).parent('th').addClass('cur');
        }
        var report = getReport();
        var board = getBoard();
        var field = getField();
        var order = getOrder();
        $('#board').attr('value', board);
        $('#field').attr('value', field);
        $('#order').attr('value', order);
        var param = diffRequest({
            'date': report,
            'board': board,
            'field': field,
            'order': order,
            'ajax': 1
        });
        changeContent(param);
    });
    //表格翻页
    $('.J-ajax-page a').live('click', function() {
        $(this).addClass('click-cur').siblings().removeClass('click-cur');
        var report = getReport();
        var board = getBoard();
        var field = getField();
        if (tjid == 'rzrqgg') {
            var order = 'desc';
        } else {
            var order = getOrder() == 'asc' ? 'desc' : 'asc';
        }
        var $page = $(this).closest('.J-ajax-page');
        var page = getPage($page);
        $('#board').attr('value', board);
        $('#field').attr('value', field);
        $('#order').attr('value', order);
        $('#page').attr('value', page);
        if (location.href.indexOf('mrtbts') > 0 || location.href.indexOf('tfpts') > 0 || location.href.indexOf('gddh') > 0 || location.href.indexOf('tbcl') > 0 || $('#rzrq').length > 0 && $('#rzrq').val() == 0) {
            var param = diffRequest({
                'date': report,
                'board': board,
                'field': field,
                'order': order,
                'page': page,
                'ajax': 1
            }, 'page');
        } else if ($('#rzrq').length > 0 && $('#rzrq').val() == 1) {
            var href = window.location.href;
            var pos2 = href.indexOf('&stockcode=');
            var code = $('#code').attr('value');
            var param = diffRequest({
                'date': report,
                'board': board,
                'field': field,
                'order': order,
                'page': page,
                'ajax': 1
            }, 'page');
        } else {
            var param = diffRequest({
                'date': report,
                'board': board,
                'field': field,
                'order': order,
                'page': page,
                'ajax': 1
            }, 'page');
        }
        if (tjid == 'hgtb') {
            var targetid = $(this).closest('.J-ajax-page').attr('targetid');
            if (targetid == 'table1') {
                var param = diffRequest({
                    'board': 'getHgtPage',
                    'page': page,
                    'ajax': 1
                }, 'page');
            }
            changeContent(param, targetid);
        } else if (tjid == 'ggtb') {
            var targetid = $(this).closest('.J-ajax-page').attr('targetid');
            if (targetid == 'table1') {
                var param = diffRequest({
                    'board': 'getGgtPage',
                    'page': page,
                    'ajax': 1
                }, 'page');
            }
            changeContent(param, targetid);
        } else if (tjid == 'ggtbs') {
            var targetid = $(this).closest('.J-ajax-page').attr('targetid');
            if (targetid == 'table1') {
                var param = diffRequest({
                    'board': 'getGgtsPage',
                    'page': page,
                    'ajax': 1
                }, 'page');
            }
            changeContent(param, targetid);
        } else if (tjid == 'sgtb') {
            var targetid = $(this).closest('.J-ajax-page').attr('targetid');
            if (targetid == 'table1') {
                var param = diffRequest({
                    'board': 'getSgtPage',
                    'page': page,
                    'ajax': 1
                }, 'page');
            }
            changeContent(param, targetid);
        } else {
            changeContent(param);
        }
    });

    //下拉框
    $('.sel-report .text,.sel-report .list').click(function() {
        $(this).parents('.sel-report').find('.list').toggle();
    });
    if ($('.sel-report .list').outerHeight() > 190) {
        $('.sel-report .list').css({
            'height': 190,
            'overflow-y': 'scroll'
        })
    }
    $('.sel-report .list a').click(function() {
        var val = $(this).text();
        var textValue = $(this).parents('.sel-report').find('.text-value');
        var date = $(this).attr('date');
        textValue.text(val);
        $('#report').attr('date', date);
        $('.J-ajax-board .J-board-item').eq(0).addClass('cur').siblings().removeClass('cur');
        var param = diffRequest({ //选择报表
            'date': date,
            'ajax': 1
        });
        changeContent(param);
    }).mouseenter(function() {
        $(this).parents('.list').find('li').removeClass('cur');
        $(this).parents('li').addClass('cur');
    });
    $(document).bind('click', function(e) {
        var $clicked = $(e.target);
        if (!$clicked.parents('.sel-report').length) {
            $('.sel-report .list').hide();
        }
    });

    //搜索框
    $('.search-box .search-list a').click(function() {
        var index = $('.search-box .search-list a').index($(this));
        $(this).addClass('cur').siblings().removeClass('cur');
        $('.search-box .search-main span').eq(index).show().siblings().hide();
    });

    //锁定表头效果
    var tableThead = function() {
        if ($('#datacenter_change_content').length > 0) {
            //固定表格每个数据项的宽度，防止在表头排序时造成宽度变动的不良体验
            //$tableThead.parent().height($tableThead.parent().height());
            $('.page-table').find('.m-table thead').find('tr th').each(function(index) {
                $(this).width($(this).width());
            });
            if ($('.fixed_thead').length <= 0) {
                //创建锁定表头
                var $fixedThead = $('<div class="fixed_thead hide"><table class="m-table J-ajax-table"></table></div>');
                $fixedThead.prependTo('.container');
                //复制表头并插入到锁定表头中
                $('#datacenter_change_content').find('.page-table .m-table thead').clone().appendTo($fixedThead.find('table'));
            } else {
                var $fixedThead = $('.fixed_thead');
                //复制最新表头并替换之前表头
                $('.fixed_thead').find('table thead').html($('#datacenter_change_content').find('.page-table .m-table thead').clone().html());
            }
            //锁定表头出现的起始位置
            var fixedTheadStartPos = $('.page-table').find('.m-table thead').offset().top;
            //锁定表头出现的最后位置
            if ($('.page-table').length > 0) {
                var fixedTheadEndPos = $('.page-table .m-page').offset().top;
            } else {
                var fixedTheadEndPos = $('.m-page').offset().top;
            }


            //屏幕滚动时锁定表头的出现和隐藏事件
            $(window).scroll(function() {
                if ($('.m-page').length <= 0) return false;
                if ($(this).scrollTop() >= fixedTheadStartPos && $(this).scrollTop() <= fixedTheadEndPos) {
                    $fixedThead.show();
                } else {
                    $fixedThead.hide();
                }
            });
        }
    };
    tableThead();


    //按键精灵个股选择及回车操作
    var clickFunc = {
        run: function(code) {
            var param = diffRequest({
                'op': 'code',
                'code': code,
                'ajax': 1
            });
            if ($('#rzrq').length > 0) {
                var stockname = $('#autocomplete_search-center .selected').text();
                stockname = escape(stockname.substr(6));
                $('#rzrq').val(1);
                var href = 'http://data.10jqka.com.cn/market/rzrqgg/code/' + code;
                window.open(href, 'newwindow');
                return false;
            }
            changeContent(param);

            $('#search-center').val(code);
        }
    };
    //主搜索框键盘精灵
    $("#search-center,#search-input-xx,#search-input-gp,#search-input-bk").focus(function() {
        if (this.value == this.defaultValue) {
            this.value = '';
        }
    }).blur(function() {
        if (!this.value) {
            this.value = this.defaultValue;
        }
    });
    $.fn.autocomplete && $("#search-center").autocomplete({
        stock: true,
        fund: false,
        hk: false,
        usa: false,
        extra: false,
        stype: 'new',
        clickFunc: [clickFunc]
    });
    $("#search-center-submit").click(function() {
        var code = $("#autocomplete_search-center dd[class='selected']").attr('data-code');
        if (!code) {
            code = $("#autocomplete_search-center dd:first").attr('data-code');
        }
        var param = 'op/code/code/' + code + '/ajax/1/';
        changeContent(param);
        $('#search-center').val(code);
    });
    //搜索信息\股票\百科
    $('#search-btn-xx,#search-btn-gp,#search-btn-bk').click(function() {
        var channel = $(this).attr('id').substr(-2, 2);
        var $input = $('#search-input-' + channel);
        var txt = $.trim($input.val());
        var defaultValue = $input.get(0).defaultValue;
        if (txt != defaultValue && txt != '') {
            switch (channel) {
                case 'xx':
                    window.open('http://www.iwencai.com/search?typed=1&preParams=&ts=1&f=1&qs=main_info&selfsectsn=&querytype=&searchfilter=&tid=info&w=' + txt);
                    break;
                case 'gp':
                    window.open('http://www.iwencai.com/stockpick/search?typed=1&preParams=&ts=1&f=1&qs=1&selfsectsn=&querytype=&searchfilter=&tid=stockpick&w=' + txt);
                    break;
                case 'bk':
                    window.open('http://www.iwencai.com/yike/search?typed=0&preParams=&ts=1&f=1&qs=query_recommend&selfsectsn=&querytype=&searchfilter=&tid=info&w=' + txt + '&isdetail=1')
                    break;
                default:
                    break;
            }
        }
    });
	
    
    var stockname = F10Utils.getUrlParams('stockname');
    var stockcode = F10Utils.getUrlParams('stockcode');
    if (stockname != false && stockcode != false) {
        var txt = '(' + stockcode + ')' + decodeURI(stockname);
        $('.crumbs').find('.cur').removeClass('cur');
        $('.crumbs').append('<span class="gt">&gt;</span><span class="cur">' + txt + '</span>');
        $('.page-tab a').removeClass('cur').attr('target', '_blank');
        $('.table-tit h2 span').html(txt);
    }
});