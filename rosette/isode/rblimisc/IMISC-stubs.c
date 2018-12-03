/* automatically generated by rosy 7.0 #14 (nanook.mcc.com), do not edit! */

#include <stdio.h>
#include "IMISC-ops.h"
#include "IMISC-types.h"


#ifdef	lint

int	stub_IMISC_utcTime (sd, id, in, rfx, efx, class, roi)
int	sd,
	id,
	class;
caddr_t in;
IFP	rfx,
	efx;
struct RoSAPindication *roi;
{
    return RyStub (sd, table_IMISC_Operations, operation_IMISC_utcTime, id, NULLIP,
		(caddr_t) in, rfx, efx, class, roi);
}

int	op_IMISC_utcTime (sd, in, out, rsp, roi)
int	sd;
caddr_t in;
caddr_t *out;
int    *rsp;
struct RoSAPindication *roi;
{
    return RyOperation (sd, table_IMISC_Operations, operation_IMISC_utcTime,
		(caddr_t) in, out, rsp, roi);
}

int	stub_IMISC_timeOfDay (sd, id, in, rfx, efx, class, roi)
int	sd,
	id,
	class;
caddr_t in;
IFP	rfx,
	efx;
struct RoSAPindication *roi;
{
    return RyStub (sd, table_IMISC_Operations, operation_IMISC_timeOfDay, id, NULLIP,
		(caddr_t) in, rfx, efx, class, roi);
}

int	op_IMISC_timeOfDay (sd, in, out, rsp, roi)
int	sd;
caddr_t in;
caddr_t *out;
int    *rsp;
struct RoSAPindication *roi;
{
    return RyOperation (sd, table_IMISC_Operations, operation_IMISC_timeOfDay,
		(caddr_t) in, out, rsp, roi);
}

int	stub_IMISC_users (sd, id, in, rfx, efx, class, roi)
int	sd,
	id,
	class;
caddr_t in;
IFP	rfx,
	efx;
struct RoSAPindication *roi;
{
    return RyStub (sd, table_IMISC_Operations, operation_IMISC_users, id, NULLIP,
		(caddr_t) in, rfx, efx, class, roi);
}

int	op_IMISC_users (sd, in, out, rsp, roi)
int	sd;
caddr_t in;
caddr_t *out;
int    *rsp;
struct RoSAPindication *roi;
{
    return RyOperation (sd, table_IMISC_Operations, operation_IMISC_users,
		(caddr_t) in, out, rsp, roi);
}

int	stub_IMISC_charGen (sd, id, in, rfx, efx, class, roi)
int	sd,
	id,
	class;
caddr_t in;
IFP	rfx,
	efx;
struct RoSAPindication *roi;
{
    return RyStub (sd, table_IMISC_Operations, operation_IMISC_charGen, id, NULLIP,
		(caddr_t) in, rfx, efx, class, roi);
}

int	op_IMISC_charGen (sd, in, out, rsp, roi)
int	sd;
caddr_t in;
caddr_t *out;
int    *rsp;
struct RoSAPindication *roi;
{
    return RyOperation (sd, table_IMISC_Operations, operation_IMISC_charGen,
		(caddr_t) in, out, rsp, roi);
}

int	stub_IMISC_qotd (sd, id, in, rfx, efx, class, roi)
int	sd,
	id,
	class;
caddr_t in;
IFP	rfx,
	efx;
struct RoSAPindication *roi;
{
    return RyStub (sd, table_IMISC_Operations, operation_IMISC_qotd, id, NULLIP,
		(caddr_t) in, rfx, efx, class, roi);
}

int	op_IMISC_qotd (sd, in, out, rsp, roi)
int	sd;
caddr_t in;
caddr_t *out;
int    *rsp;
struct RoSAPindication *roi;
{
    return RyOperation (sd, table_IMISC_Operations, operation_IMISC_qotd,
		(caddr_t) in, out, rsp, roi);
}

int	stub_IMISC_finger (sd, id, in, rfx, efx, class, roi)
int	sd,
	id,
	class;
struct type_IMISC_IA5List* in;
IFP	rfx,
	efx;
struct RoSAPindication *roi;
{
    return RyStub (sd, table_IMISC_Operations, operation_IMISC_finger, id, NULLIP,
		(caddr_t) in, rfx, efx, class, roi);
}

int	op_IMISC_finger (sd, in, out, rsp, roi)
int	sd;
struct type_IMISC_IA5List* in;
caddr_t *out;
int    *rsp;
struct RoSAPindication *roi;
{
    return RyOperation (sd, table_IMISC_Operations, operation_IMISC_finger,
		(caddr_t) in, out, rsp, roi);
}

int	stub_IMISC_pwdGen (sd, id, in, rfx, efx, class, roi)
int	sd,
	id,
	class;
caddr_t in;
IFP	rfx,
	efx;
struct RoSAPindication *roi;
{
    return RyStub (sd, table_IMISC_Operations, operation_IMISC_pwdGen, id, NULLIP,
		(caddr_t) in, rfx, efx, class, roi);
}

int	op_IMISC_pwdGen (sd, in, out, rsp, roi)
int	sd;
caddr_t in;
caddr_t *out;
int    *rsp;
struct RoSAPindication *roi;
{
    return RyOperation (sd, table_IMISC_Operations, operation_IMISC_pwdGen,
		(caddr_t) in, out, rsp, roi);
}

int	stub_IMISC_genTime (sd, id, in, rfx, efx, class, roi)
int	sd,
	id,
	class;
caddr_t in;
IFP	rfx,
	efx;
struct RoSAPindication *roi;
{
    return RyStub (sd, table_IMISC_Operations, operation_IMISC_genTime, id, NULLIP,
		(caddr_t) in, rfx, efx, class, roi);
}

int	op_IMISC_genTime (sd, in, out, rsp, roi)
int	sd;
caddr_t in;
caddr_t *out;
int    *rsp;
struct RoSAPindication *roi;
{
    return RyOperation (sd, table_IMISC_Operations, operation_IMISC_genTime,
		(caddr_t) in, out, rsp, roi);
}

int	stub_IMISC_tellUser (sd, id, in, rfx, efx, class, roi)
int	sd,
	id,
	class;
struct type_IMISC_IA5List* in;
IFP	rfx,
	efx;
struct RoSAPindication *roi;
{
    return RyStub (sd, table_IMISC_Operations, operation_IMISC_tellUser, id, NULLIP,
		(caddr_t) in, rfx, efx, class, roi);
}

int	op_IMISC_tellUser (sd, in, out, rsp, roi)
int	sd;
struct type_IMISC_IA5List* in;
caddr_t *out;
int    *rsp;
struct RoSAPindication *roi;
{
    return RyOperation (sd, table_IMISC_Operations, operation_IMISC_tellUser,
		(caddr_t) in, out, rsp, roi);
}

int	stub_IMISC_ping (sd, id, in, rfx, efx, class, roi)
int	sd,
	id,
	class;
struct type_IMISC_Empty* in;
IFP	rfx,
	efx;
struct RoSAPindication *roi;
{
    return RyStub (sd, table_IMISC_Operations, operation_IMISC_ping, id, NULLIP,
		(caddr_t) in, rfx, efx, class, roi);
}

int	op_IMISC_ping (sd, in, out, rsp, roi)
int	sd;
struct type_IMISC_Empty* in;
caddr_t *out;
int    *rsp;
struct RoSAPindication *roi;
{
    return RyOperation (sd, table_IMISC_Operations, operation_IMISC_ping,
		(caddr_t) in, out, rsp, roi);
}

int	stub_IMISC_sink (sd, id, in, rfx, efx, class, roi)
int	sd,
	id,
	class;
struct type_IMISC_Data* in;
IFP	rfx,
	efx;
struct RoSAPindication *roi;
{
    return RyStub (sd, table_IMISC_Operations, operation_IMISC_sink, id, NULLIP,
		(caddr_t) in, rfx, efx, class, roi);
}

int	op_IMISC_sink (sd, in, out, rsp, roi)
int	sd;
struct type_IMISC_Data* in;
caddr_t *out;
int    *rsp;
struct RoSAPindication *roi;
{
    return RyOperation (sd, table_IMISC_Operations, operation_IMISC_sink,
		(caddr_t) in, out, rsp, roi);
}

int	stub_IMISC_echo (sd, id, in, rfx, efx, class, roi)
int	sd,
	id,
	class;
struct type_IMISC_Data* in;
IFP	rfx,
	efx;
struct RoSAPindication *roi;
{
    return RyStub (sd, table_IMISC_Operations, operation_IMISC_echo, id, NULLIP,
		(caddr_t) in, rfx, efx, class, roi);
}

int	op_IMISC_echo (sd, in, out, rsp, roi)
int	sd;
struct type_IMISC_Data* in;
caddr_t *out;
int    *rsp;
struct RoSAPindication *roi;
{
    return RyOperation (sd, table_IMISC_Operations, operation_IMISC_echo,
		(caddr_t) in, out, rsp, roi);
}
#endif
