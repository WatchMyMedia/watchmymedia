package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"time"

	"watchmymedia/server/internal/plex"
)

type authProbeReport struct {
	Schema              int           `json:"schema"`
	Authentication      capability    `json:"authentication"`
	CredentialRenewal   capability    `json:"credentialRenewal"`
	OwnerResource       capability    `json:"ownerResource"`
	PMSIdentity         capability    `json:"pmsIdentityPreflight"`
	ResourcePMSAccess   authPMSAccess `json:"resourcePmsAccess"`
	OwnerDeviceBridge   capability    `json:"ownerDeviceBridge"`
	PMSCredentialSource string        `json:"pmsCredentialSource"`
	HistorySubject      string        `json:"historySubjectBinding"`
	PMS                 *probeReport  `json:"pms,omitempty"`
}

type authPMSAccess struct {
	capability
	HTTPStatus int `json:"httpStatus,omitempty"`
}

// These dependencies exist only for synthetic tests. The executable has no
// endpoint override for Plex authentication or resource discovery.
type authProbeDeps struct {
	clientsOrigin string
	accountOrigin string
	httpClient    *http.Client
	wait          func(context.Context, time.Duration) error
}

var authMachineID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,256}$`)

func runAuth(ctx context.Context, getenv func(string) string, out, diagnostics io.Writer) int {
	return runAuthWithDeps(ctx, getenv, out, diagnostics, authProbeDeps{
		clientsOrigin: "https://clients.plex.tv", accountOrigin: "https://plex.tv", wait: authPollWait,
	})
}

func runAuthWithDeps(ctx context.Context, getenv func(string) string, out, diagnostics io.Writer, deps authProbeDeps) int {
	// Freeze the configuration before any remote call. In particular, never read
	// the caller's existing token, even when it happens to be in the environment.
	origin := getenv("WMM_PLEX_PROBE_URL")
	machine := getenv("WMM_PLEX_PROBE_MACHINE_ID")
	section := getenv("WMM_PLEX_PROBE_SECTION_ID")
	cloudID, cloudErr := strconv.ParseInt(getenv("WMM_PLEX_PROBE_CLOUD_ACCOUNT_ID"), 10, 64)
	sectionID, sectionErr := strconv.ParseInt(section, 10, 64)
	if origin == "" || !authMachineID.MatchString(machine) || sectionErr != nil || sectionID <= 0 || cloudErr != nil || cloudID <= 0 {
		_, _ = io.WriteString(diagnostics, "Invalid or missing authentication probe configuration. No requests made.\n")
		return 2
	}
	historyAccountID, historySince, historyErr := parseHistoryConfig(getenv)
	if historyErr != nil {
		_, _ = fmt.Fprintln(diagnostics, historyErr)
		return 2
	}
	historyID, historySinceValue := "", ""
	if historyAccountID != 0 {
		historyID = strconv.FormatInt(historyAccountID, 10)
	}
	if !historySince.IsZero() {
		historySinceValue = historySince.Format(time.RFC3339)
	}
	var identifier [16]byte
	if _, err := rand.Read(identifier[:]); err != nil {
		_, _ = io.WriteString(diagnostics, "Could not create a probe identifier. No requests made.\n")
		return 1
	}
	opts := plex.Options{ClientIdentifier: "wmm-plex-auth-probe-" + hex.EncodeToString(identifier[:]), HTTPClient: deps.httpClient}
	if _, err := plex.NewClient(origin, "", opts); err != nil {
		_, _ = io.WriteString(diagnostics, "Invalid server origin. No requests made.\n")
		return 2
	}
	key, err := plex.GenerateDeviceKey()
	if err != nil {
		_, _ = io.WriteString(diagnostics, "Could not create a probe key. No requests made.\n")
		return 1
	}
	pinClient, err := plex.NewClient(deps.clientsOrigin, "", opts)
	if err != nil {
		_, _ = io.WriteString(diagnostics, "Invalid authentication endpoint. No requests made.\n")
		return 2
	}
	// Five minutes also bounds callers that did not supply their own deadline.
	ctx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	report := authProbeReport{Schema: 1,
		Authentication: capability{Status: "not_run"}, CredentialRenewal: capability{Status: "not_run"}, OwnerResource: capability{Status: "not_run"},
		PMSIdentity: capability{Status: "not_run"}, ResourcePMSAccess: authPMSAccess{capability: capability{Status: "not_run"}},
		OwnerDeviceBridge: capability{Status: "not_run"}, PMSCredentialSource: "not_selected",
		HistorySubject: "not_requested",
	}
	if historyID != "" {
		report.HistorySubject = "caller_supplied_pms_account_id_not_verified"
	}
	write := func(code int) int {
		encoder := json.NewEncoder(out)
		encoder.SetIndent("", "  ")
		if encoder.Encode(report) != nil {
			_, _ = io.WriteString(diagnostics, "Could not write authentication probe report.\n")
			return 1
		}
		return code
	}
	pin, err := pinClient.CreatePIN(ctx, key)
	if err != nil {
		report.Authentication = capability{Status: "failed", Reason: plex.ErrorKind(err)}
		return write(1)
	}
	// This is a transient interaction URL, deliberately separate from the JSON
	// compatibility report. No access token or private key is printed.
	if _, err := fmt.Fprintln(diagnostics, "Open this temporary Plex sign-in link to authorize the new probe device:\n"+pin.AuthURL()); err != nil {
		report.Authentication = capability{Status: "failed", Reason: "interaction_output"}
		return write(1)
	}
	var credential plex.Credential
	for {
		var ready bool
		credential, ready, err = pinClient.PollPIN(ctx, pin, key)
		if err != nil {
			report.Authentication = capability{Status: "failed", Reason: plex.ErrorKind(err)}
			return write(1)
		}
		if ready {
			break
		}
		if err := deps.wait(ctx, 2*time.Second); err != nil {
			report.Authentication = capability{Status: "failed", Reason: plex.ErrorKind(err)}
			return write(1)
		}
	}
	verifyAccount := func(token string) (plex.Account, error) {
		client, err := plex.NewClient(deps.accountOrigin, token, opts)
		if err != nil {
			return plex.Account{}, err
		}
		account, err := client.Account(ctx)
		if err != nil {
			return plex.Account{}, err
		}
		if account.ID != cloudID {
			return plex.Account{}, &plex.Error{Kind: "identity_mismatch"}
		}
		if account.Restricted {
			return plex.Account{}, &plex.Error{Kind: "invalid_identity"}
		}
		return account, nil
	}
	owner, err := verifyAccount(credential.Token())
	if err != nil {
		report.Authentication = capability{Status: "failed", Reason: plex.ErrorKind(err)}
		return write(1)
	}
	report.Authentication = capability{Status: "account_verified"}
	credential, err = pinClient.RefreshJWT(ctx, key)
	if err == nil {
		owner, err = verifyAccount(credential.Token())
	}
	if err != nil {
		report.CredentialRenewal = capability{Status: "failed", Reason: plex.ErrorKind(err)}
		return write(1)
	}
	report.CredentialRenewal = capability{Status: "account_verified"}
	resourceClient, err := plex.NewClient(deps.clientsOrigin, credential.Token(), opts)
	if err != nil {
		report.OwnerResource = capability{Status: "failed", Reason: plex.ErrorKind(err)}
		return write(1)
	}
	resources, err := resourceClient.Resources(ctx)
	if err != nil {
		report.OwnerResource = capability{Status: "failed", Reason: plex.ErrorKind(err)}
		return write(1)
	}
	var selected *plex.Resource
	matches := 0
	for i := range resources {
		if resources[i].MachineIdentifier == machine {
			selected = &resources[i]
			matches++
		}
	}
	if matches != 1 || selected == nil || !selected.Owned || selected.Credential.Token() == "" {
		report.OwnerResource = capability{Status: "failed", Reason: "owned_server_credential_unavailable"}
		return write(1)
	}
	report.OwnerResource = capability{Status: "matched"}
	identityClient, err := plex.NewClient(origin, "", opts)
	if err == nil {
		_, err = identityClient.VerifyIdentity(ctx, machine)
	}
	if err != nil {
		report.PMSIdentity = capability{Status: "failed", Reason: plex.ErrorKind(err)}
		return write(1)
	}
	report.PMSIdentity = capability{Status: "matched"}
	serverToken := selected.Credential.Token()
	pmsClient, err := plex.NewClient(origin, serverToken, opts)
	if err == nil {
		_, err = pmsClient.Libraries(ctx)
	}
	if err != nil {
		report.ResourcePMSAccess = authPMSAccess{Status: "failed", Reason: plex.ErrorKind(err)}
		var plexErr *plex.Error
		if errors.As(err, &plexErr) {
			report.ResourcePMSAccess.HTTPStatus = plexErr.StatusCode
		}
		// A forbidden response, network failure, malformed payload or missing
		// library never justifies changing credentials. Only the observed PMS
		// resource-token HTTP 401 compatibility case permits this owner bridge.
		if plexErr == nil || plexErr.StatusCode != http.StatusUnauthorized {
			return write(1)
		}
		deviceCredential, err := resourceClient.OwnerDeviceCredential(ctx, owner, *selected)
		if err != nil {
			report.OwnerDeviceBridge = capability{Status: "failed", Reason: plex.ErrorKind(err)}
			return write(1)
		}
		report.OwnerDeviceBridge = capability{Status: "credential_selected"}
		// Recheck the explicit destination before handing it the stronger owner
		// device credential. run also checks identity immediately before reads.
		if _, err := identityClient.VerifyIdentity(ctx, machine); err != nil {
			report.PMSIdentity = capability{Status: "failed", Reason: plex.ErrorKind(err)}
			return write(1)
		}
		serverToken = deviceCredential.Token()
		report.PMSCredentialSource = "owner_device"
	} else {
		report.ResourcePMSAccess = authPMSAccess{Status: "libraries_read"}
		report.PMSCredentialSource = "owner_resource"
	}
	values := map[string]string{
		"WMM_PLEX_PROBE_URL": origin, "WMM_PLEX_PROBE_TOKEN": serverToken,
		"WMM_PLEX_PROBE_MACHINE_ID": machine, "WMM_PLEX_PROBE_SECTION_ID": section,
		"WMM_PLEX_PROBE_ACCOUNT_ID":    historyID,
		"WMM_PLEX_PROBE_HISTORY_SINCE": historySinceValue,
		"WMM_PLEX_PROBE_CLIENT_ID":     opts.ClientIdentifier,
	}
	var pms bytes.Buffer
	code := run(ctx, []string{"-live"}, func(name string) string { return values[name] }, &pms, diagnostics)
	var pmsReport probeReport
	if json.Unmarshal(pms.Bytes(), &pmsReport) != nil {
		report.OwnerResource = capability{Status: "failed", Reason: "pms_probe_report_unavailable"}
		return write(1)
	}
	// Current state comes from the verified owner's explicit server resource,
	// or the separately reported exact owner device bridge after HTTP 401.
	// A PMS history account ID, if supplied, remains independently caller-bound.
	pmsReport.SubjectBinding = "owner_cloud_account_and_server_resource_verified"
	report.PMS = &pmsReport
	return write(code)
}

func authPollWait(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
