<#-- $This file is distributed under the terms of the license in LICENSE$ -->

<#--Breaking this out so this can be utilized by other pages such as the jsp advanced tools pages-->

<section id="search" role="region">
    <fieldset>
        <legend>${i18n().search_form}</legend>

        <form id="search-form" action="${urls.search}" autocomplete="off" name="search" role="search" accept-charset="UTF-8" method="GET">
            <div id="search-field">
                <input type="text" id="filter_input_querytext" name="querytext" class="search-vivo" value="${querytext!?html}" autocapitalize="off" />
                <input type="submit" value="${i18n().search_button}" class="search">
                <a href="${urls.base}/askVivo" class="ask-vivo-button" title="Ask a question in plain language">Ask VIVO</a>
            </div>
        </form>
    </fieldset>
</section>

