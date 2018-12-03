-- automatically generated by posy 7.0 #14 (nanook.mcc.com), do not edit!

IMISC DEFINITIONS ::=

%{
#include <stdio.h>
#include "IMISC-types.h"
%}

PREFIXES encode decode print

BEGIN


ENCODER encode

IA5List [[P struct type_IMISC_IA5List *]] ::=
        SEQUENCE OF
            <<; parm; parm = parm -> next>>
            IA5String
            [[p parm -> IA5String ]]

UTCResult [[P struct type_IMISC_UTCResult *]] ::=
        UniversalTime
        [[p parm ]]

TimeResult [[P struct type_IMISC_TimeResult *]] ::=
        INTEGER
        [[i parm -> parm ]]

GenResult [[P struct type_IMISC_GenResult *]] ::=
        GeneralizedTime
        [[p parm ]]

Data [[P struct type_IMISC_Data *]] ::=
        ANY
        [[a parm ]]

Empty [[P struct type_IMISC_Empty *]] ::=
        NULL

DECODER decode

IA5List [[P struct type_IMISC_IA5List **]] ::=
        SEQUENCE OF
            %{
                if ((*(parm) = (struct type_IMISC_IA5List *)
                        calloc (1, sizeof **(parm))) == ((struct type_IMISC_IA5List *) 0)) {
                    advise (NULLCP, "%s", PEPY_ERR_NOMEM);
                    return NOTOK;
                }
            %}
            IA5String
            [[p &((*parm) -> IA5String)]]
            %{ parm = &((*parm) -> next); %}

UTCResult [[P struct type_IMISC_UTCResult **]] ::=
        UniversalTime
        [[p &((*parm))]]

TimeResult [[P struct type_IMISC_TimeResult **]] ::=
    %{
        if ((*(parm) = (struct type_IMISC_TimeResult *)
                calloc (1, sizeof **(parm))) == ((struct type_IMISC_TimeResult *) 0)) {
            advise (NULLCP, "%s", PEPY_ERR_NOMEM);
            return NOTOK;
        }
    %}
        INTEGER
        [[i (*parm) -> parm ]]

GenResult [[P struct type_IMISC_GenResult **]] ::=
        GeneralizedTime
        [[p &((*parm))]]

Data [[P struct type_IMISC_Data **]] ::=
        ANY
        [[a (*parm) ]]

Empty [[P struct type_IMISC_Empty **]] ::=
    %{
        if ((*(parm) = (struct type_IMISC_Empty *)
                calloc (1, sizeof **(parm))) == ((struct type_IMISC_Empty *) 0)) {
            advise (NULLCP, "%s", PEPY_ERR_NOMEM);
            return NOTOK;
        }
    %}
        NULL

END

%{

free_IMISC_IA5List (arg)
struct type_IMISC_IA5List *arg;
{
    struct type_IMISC_IA5List *parm = arg;

    if (parm == NULL)
	return;

    for (parm = parm; parm;) {
        struct type_IMISC_IA5List *f_parm = parm -> next;

        if (parm -> IA5String)
            free_UNIV_IA5String (parm -> IA5String),
                parm -> IA5String = NULL;

        if (parm)
            free ((char *) parm);
        parm = f_parm;
    }

}

free_IMISC_TimeResult (arg)
struct type_IMISC_TimeResult *arg;
{
    struct type_IMISC_TimeResult *parm = arg;

    if (parm == NULL)
	return;


    free ((char *) arg);
}

free_IMISC_Empty (arg)
struct type_IMISC_Empty *arg;
{
    struct type_IMISC_Empty *parm = arg;

    if (parm == NULL)
	return;


    free ((char *) arg);
}

%}
